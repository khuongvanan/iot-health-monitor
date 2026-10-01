/*
 * ESP32 Healthcare - Full Integration
 * BLE (CMD + DATA) + MQTT + HTTP POST
 *
 * Luồng:
 *  1. BLE advertise sẵn, chờ app kết nối
 *  2. App gửi CMD qua BLE Write HOẶC MQTT sensor/cmd
 *  3. ESP32 đo HR+SpO2 → nhiệt độ → tạo rid
 *  4. Gửi đồng thời: BLE notify + MQTT publish + HTTP POST
 *
 * FIX (bug đo 2 lần khi app gửi cùng lúc MQTT + BLE):
 *  - Cờ `isMeasuring` khoá chu kỳ đo, chặn START_MEASURE trùng
 *  - Drain mqttClient.loop() ~1.5s sau khi đo xong (vẫn còn khoá)
 *    → xử lý hết gói MQTT bị broker giữ trong lúc đang đo
 *  - Debounce `lastMeasureStartMs` 10s — backup
 */

#include <Wire.h>
#include "MAX30105.h"
#include "spo2_algorithm.h"
#include <Adafruit_MLX90614.h>

// BLE
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// WiFi + MQTT + HTTP
#include <WiFi.h>
#include <PubSubClient.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>

// ─────────────────────────────────────────────
//  CẤU HÌNH — chỉnh tại đây
// ─────────────────────────────────────────────
// Thông tin WiFi / MQTT / HTTP nằm trong config.h (không commit lên Git).
// Tạo config.h bằng cách copy config.example.h rồi điền giá trị thật.
#include "config.h"

#define TOPIC_DATA      "sensor/data"
#define TOPIC_STATUS    "sensor/status"
#define TOPIC_CMD       "sensor/cmd"

// ─────────────────────────────────────────────
//  BLE UUIDs
// ─────────────────────────────────────────────
#define BLE_SERVICE_UUID        "12345678-1234-1234-1234-123456789abc"
#define BLE_CHAR_CMD_UUID       "12345678-1234-1234-1234-123456789001"  // Write: nhận lệnh từ app
#define BLE_CHAR_DATA_UUID      "12345678-1234-1234-1234-123456789002"  // Notify: gửi kết quả về app
#define BLE_CHAR_STATUS_UUID    "12345678-1234-1234-1234-123456789003"  // Notify: trạng thái tiến trình

// Lệnh từ app
#define CMD_START_MEASURE   "START_MEASURE"
#define CMD_CANCEL          "CANCEL"
#define CMD_STATUS_CHECK    "STATUS_CHECK"

// Cửa sổ chống trùng (ms) — sau lần START_MEASURE thành công,
// mọi lệnh START_MEASURE đến trong cửa sổ này đều bị bỏ qua.
// 10s đủ rộng để cover gói MQTT bị giữ trong lúc đang đo (~5-8s).
#define MEASURE_DEDUP_MS    10000UL

// ─────────────────────────────────────────────
//  Phần cứng
// ─────────────────────────────────────────────
#define MAX_SDA 21
#define MAX_SCL 22
#define MLX_SDA 16
#define MLX_SCL 17

#define FINGER_THRESHOLD 7000
#define HR_SCALE         0.65f
#define TEMP_OFFSET      2.0f

TwoWire I2C_MAX = TwoWire(0);
TwoWire I2C_MLX = TwoWire(1);
MAX30105 particleSensor;
Adafruit_MLX90614 mlx = Adafruit_MLX90614();

// ─────────────────────────────────────────────
//  State
// ─────────────────────────────────────────────
volatile bool measureRequested = false;   // có lệnh đo đang chờ vào chu kỳ
volatile bool isMeasuring      = false;   // đang trong chu kỳ đo (lock chống trùng)
volatile bool cancelRequested  = false;   // lệnh huỷ
bool          bleConnected     = false;

// timestamp lần START_MEASURE được chấp nhận gần nhất (millis)
// dùng để debounce: chặn lệnh trùng đến trong vòng MEASURE_DEDUP_MS
unsigned long lastMeasureStartMs = 0;

// userId của phiên đo hiện tại (do app gửi kèm khi START_MEASURE).
// 0 = không xác định → server sẽ fallback về devices.registered_by.
volatile int  currentUserId    = 0;

float finalHR = 0, finalSpO2 = 0, finalTemp = 0, finalAmbient = 0;

// ─────────────────────────────────────────────
//  BLE objects
// ─────────────────────────────────────────────
BLEServer*          pServer      = nullptr;
BLECharacteristic*  pCharData    = nullptr;
BLECharacteristic*  pCharStatus  = nullptr;

// ─────────────────────────────────────────────
//  WiFi / MQTT
// ─────────────────────────────────────────────
WiFiClient   wifiClient;
PubSubClient mqttClient(wifiClient);

// ─────────────────────────────────────────────
//  Forward declarations
// ─────────────────────────────────────────────
void sendBleStatus(const char* msg);
void sendBleResult();
void mqttPublishResult(const String& rid);
void httpPostResult(const String& rid);
String generateRid();
bool doMeasureHeartRate();
bool doMeasureTemperature();
void sortArray(float arr[], int n);
void processCommand(String cmd, const char* source);
void sendSensorStatus(const char* source);

// ═════════════════════════════════════════════
//  BLE Callbacks
// ═════════════════════════════════════════════
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pSvr) override {
    bleConnected = true;
    Serial.println("[BLE] App kết nối");
  }
  void onDisconnect(BLEServer* pSvr) override {
    bleConnected = false;
    Serial.println("[BLE] App ngắt kết nối — bắt đầu advertise lại");
    pSvr->startAdvertising();
  }
};

class CmdCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* pChar) override {
    String cmd = pChar->getValue();
    Serial.print("[BLE CMD] Nhận: "); Serial.println(cmd);
    processCommand(cmd, "BLE");
  }
};

// ═════════════════════════════════════════════
//  MQTT Callback
// ═════════════════════════════════════════════
void mqttCallback(char* topic, byte* payload, unsigned int length) {
  String msg;
  for (unsigned int i = 0; i < length; i++) msg += (char)payload[i];
  Serial.print("[MQTT CMD] topic="); Serial.print(topic);
  Serial.print(" msg="); Serial.println(msg);

  if (String(topic) == TOPIC_CMD) {
    processCommand(msg, "MQTT");
  }
}

// ═════════════════════════════════════════════
//  Process command — chấp nhận cả 2 format:
//    1. Chuỗi thuần:  "START_MEASURE" hoặc "CANCEL"     (legacy, uid=0)
//    2. JSON:         {"cmd":"START_MEASURE","uid":3}   (Model B multi-user)
//
//  Chống trùng cho START_MEASURE (app gửi cùng lúc MQTT + BLE):
//    - Lớp 1: cờ isMeasuring/measureRequested → chặn khi đang xử lý
//    - Lớp 2: debounce MEASURE_DEDUP_MS → chặn gói trùng đến sau
// ═════════════════════════════════════════════
void processCommand(String cmd, const char* source) {
  cmd.trim();
  if (cmd.length() == 0) return;

  String action = "";
  int    uid    = 0;

  if (cmd.charAt(0) == '{') {
    // Format JSON
    StaticJsonDocument<128> doc;
    DeserializationError err = deserializeJson(doc, cmd);
    if (err) {
      Serial.print("[CMD/"); Serial.print(source);
      Serial.print("] JSON parse error: "); Serial.println(err.c_str());
      return;
    }
    if (doc.containsKey("cmd")) action = doc["cmd"].as<String>();
    if (doc.containsKey("uid")) uid    = doc["uid"].as<int>();
  } else {
    // Format chuỗi thuần (backward compatible)
    action = cmd;
  }

  Serial.print("[CMD/"); Serial.print(source);
  Serial.print("] action="); Serial.print(action);
  Serial.print(" uid="); Serial.println(uid);

  if (action == CMD_START_MEASURE) {
    unsigned long now = millis();

    // ── Lớp 1: đang đo / đã có request chờ → bỏ qua ──
    if (measureRequested || isMeasuring) {
      Serial.print("[CMD/"); Serial.print(source);
      Serial.println("] BỎ QUA — đang đo (lock)");
      return;
    }

    // ── Lớp 2: debounce — lệnh đến trong cửa sổ MEASURE_DEDUP_MS sau lệnh trước ──
    // Đây chính là gói MQTT bị broker giữ trong lúc ESP32 đang chạy doMeasure...()
    // và mới được mqttClient.loop() xử lý sau khi đo xong.
    if (lastMeasureStartMs != 0 && (now - lastMeasureStartMs) < MEASURE_DEDUP_MS) {
      Serial.print("[CMD/"); Serial.print(source);
      Serial.print("] BỎ QUA duplicate — lệnh trước cách đây ");
      Serial.print((now - lastMeasureStartMs));
      Serial.println(" ms");
      return;
    }

    // ── Chấp nhận lệnh — kích hoạt đo ──
    lastMeasureStartMs = now;
    measureRequested   = true;
    cancelRequested    = false;
    currentUserId      = uid;
    Serial.print("[CMD/"); Serial.print(source);
    Serial.print("] → Kích hoạt đo cho uid="); Serial.println(uid);

  } else if (action == CMD_CANCEL) {
    cancelRequested  = true;
    measureRequested = false;
    Serial.print("[CMD/"); Serial.print(source); Serial.println("] → Huỷ đo");

  } else if (action == CMD_STATUS_CHECK) {
    Serial.print("[CMD/"); Serial.print(source); Serial.println("] → Kiểm tra cảm biến");
    sendSensorStatus(source);
  }
}

// ═════════════════════════════════════════════
//  Kiểm tra trạng thái cảm biến và publish
//  Gửi qua MQTT topic sensor/status và BLE STATUS characteristic.
//  Định dạng JSON:
//    {"code":"SENSOR_STATUS","max30102":"OK","mlx90614":"OK","devId":"ESP32_H1"}
// ═════════════════════════════════════════════
void sendSensorStatus(const char* source) {
  // Test MAX30102 — ping I2C address 0x57 trên bus I2C_MAX
  I2C_MAX.beginTransmission(0x57);
  uint8_t errMax = I2C_MAX.endTransmission();
  bool maxOk = (errMax == 0);

  // Test MLX90614 — ping I2C address 0x5A trên bus I2C_MLX
  I2C_MLX.beginTransmission(0x5A);
  uint8_t errMlx = I2C_MLX.endTransmission();
  bool mlxOk = (errMlx == 0);

  Serial.print("[SENSOR] MAX30102=");
  Serial.print(maxOk ? "OK" : "ERR");
  Serial.print("(err="); Serial.print(errMax); Serial.print(")");
  Serial.print("  MLX90614=");
  Serial.print(mlxOk ? "OK" : "ERR");
  Serial.print("(err="); Serial.print(errMlx); Serial.println(")");

  // Tạo JSON
  StaticJsonDocument<160> doc;
  doc["code"]     = "SENSOR_STATUS";
  doc["max30102"] = maxOk ? "OK" : "ERR";
  doc["mlx90614"] = mlxOk ? "OK" : "ERR";
  doc["devId"]    = DEVICE_ID;
  String json;
  serializeJson(doc, json);

  // Publish MQTT (nếu broker còn kết nối)
  if (mqttClient.connected()) {
    mqttClient.publish(TOPIC_STATUS, json.c_str());
    Serial.print("[MQTT] Publish SENSOR_STATUS: "); Serial.println(json);
  } else {
    Serial.println("[MQTT] Chưa kết nối, không publish được SENSOR_STATUS");
  }

  // Gửi qua BLE Notify (nếu app BLE đang kết nối)
  if (bleConnected && pCharStatus != nullptr) {
    pCharStatus->setValue(json.c_str());
    pCharStatus->notify();
    Serial.println("[BLE] Notify SENSOR_STATUS");
  }
}

// ═════════════════════════════════════════════
//  Setup BLE
// ═════════════════════════════════════════════
void setupBLE() {
  BLEDevice::init(DEVICE_ID);
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  BLEService* pService = pServer->createService(BLE_SERVICE_UUID);

  // CMD characteristic: Write
  BLECharacteristic* pCharCmd = pService->createCharacteristic(
    BLE_CHAR_CMD_UUID,
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
  );
  pCharCmd->setCallbacks(new CmdCallbacks());

  // DATA characteristic: Notify
  pCharData = pService->createCharacteristic(
    BLE_CHAR_DATA_UUID,
    BLECharacteristic::PROPERTY_NOTIFY
  );
  pCharData->addDescriptor(new BLE2902());

  // STATUS characteristic: Notify (hiện tiến trình cho app)
  pCharStatus = pService->createCharacteristic(
    BLE_CHAR_STATUS_UUID,
    BLECharacteristic::PROPERTY_NOTIFY
  );
  pCharStatus->addDescriptor(new BLE2902());

  pService->start();

  BLEAdvertising* pAdv = BLEDevice::getAdvertising();
  pAdv->addServiceUUID(BLE_SERVICE_UUID);
  pAdv->setScanResponse(true);
  pAdv->setMinPreferred(0x06);
  BLEDevice::startAdvertising();

  Serial.println("[BLE] Advertise bắt đầu. Tên: " DEVICE_ID);
}

// ═════════════════════════════════════════════
//  Setup WiFi
// ═════════════════════════════════════════════
void setupWiFi() {
  Serial.print("[WiFi] Kết nối " WIFI_SSID " ...");
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  unsigned long t = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t < 10000) {
    delay(500); Serial.print(".");
  }
  if (WiFi.status() == WL_CONNECTED) {
    Serial.print(" OK! IP="); Serial.println(WiFi.localIP());
  } else {
    Serial.println(" FAIL (tiếp tục không có WiFi)");
  }
}

// ═════════════════════════════════════════════
//  MQTT reconnect (non-blocking attempt)
// ═════════════════════════════════════════════
void mqttReconnect() {
  if (mqttClient.connected()) return;
  if (WiFi.status() != WL_CONNECTED) return;
  Serial.print("[MQTT] Kết nối...");
  if (mqttClient.connect(MQTT_CLIENT_ID)) {
    Serial.println(" OK");
    mqttClient.subscribe(TOPIC_CMD);
    // Gửi online status
    mqttClient.publish(TOPIC_STATUS, "{\"status\":\"online\",\"device\":\"" DEVICE_ID "\"}");
  } else {
    Serial.print(" FAIL rc="); Serial.println(mqttClient.state());
  }
}

// ═════════════════════════════════════════════
//  Helpers
// ═════════════════════════════════════════════
void sortArray(float arr[], int n) {
  for (int i = 0; i < n - 1; i++)
    for (int j = 0; j < n - i - 1; j++)
      if (arr[j] > arr[j + 1]) { float t = arr[j]; arr[j] = arr[j+1]; arr[j+1] = t; }
}

String generateRid() {
  // rid = DEVICE_ID + "_" + epoch-giây (dùng millis nếu chưa có NTP)
  return String(DEVICE_ID) + "_" + String(millis() / 1000UL);
}

// Publish trạng thái qua MQTT sensor/status dạng JSON {code, msg, devId}
// App (AppNetwork.MqttHelper.parseMessage) parse đúng format này
void mqttPublishStatus(const char* code) {
  if (!mqttClient.connected()) return;
  StaticJsonDocument<128> doc;
  doc["code"]  = code;
  doc["msg"]   = code;  // dùng code làm msg luôn, app map ra tiếng Việt
  doc["devId"] = DEVICE_ID;
  char buf[128];
  serializeJson(doc, buf);
  mqttClient.publish(TOPIC_STATUS, buf);
}

// Gửi trạng thái tiến trình lên app qua BLE STATUS và MQTT sensor/status
void sendBleStatus(const char* msg) {
  Serial.print("[STATUS] "); Serial.println(msg);
  // BLE notify (nếu app đang kết nối)
  if (bleConnected && pCharStatus) {
    pCharStatus->setValue((uint8_t*)msg, strlen(msg));
    pCharStatus->notify();
  }
  // MQTT publish (để MonitoringService nhận khi không có BLE)
  mqttPublishStatus(msg);
}

// Gửi kết quả đo về app qua BLE DATA characteristic (JSON)
void sendBleResult(const String& rid) {
  if (!bleConnected || !pCharData) return;

  StaticJsonDocument<256> doc;
  doc["devId"]    = DEVICE_ID;
  doc["rid"]      = rid;
  doc["hr"]       = (int)finalHR;
  doc["spo2"]     = (finalSpO2 > 0) ? (int)finalSpO2 : 0;
  doc["temp"]     = serialized(String(finalTemp, 1));
  doc["ambient"]  = serialized(String(finalAmbient, 1));

  char buf[256];
  serializeJson(doc, buf);

  pCharData->setValue((uint8_t*)buf, strlen(buf));
  pCharData->notify();
  Serial.print("[BLE DATA] Gửi: "); Serial.println(buf);
}

// Publish kết quả lên MQTT sensor/data
void mqttPublishResult(const String& rid) {
  if (!mqttClient.connected()) return;

  StaticJsonDocument<256> doc;
  doc["devId"]    = DEVICE_ID;
  doc["rid"]      = rid;
  doc["hr"]       = (int)finalHR;
  doc["spo2"]     = (finalSpO2 > 0) ? (int)finalSpO2 : 0;
  doc["temp"]     = serialized(String(finalTemp, 1));
  doc["ambient"]  = serialized(String(finalAmbient, 1));
  if (currentUserId > 0) doc["userId"] = currentUserId;

  char buf[256];
  serializeJson(doc, buf);

  mqttClient.publish(TOPIC_DATA, buf);
  Serial.print("[MQTT] Publish sensor/data: "); Serial.println(buf);
}

// HTTP POST lên server
void httpPostResult(const String& rid) {
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("[HTTP] Không có WiFi, bỏ qua");
    return;
  }

  HTTPClient http;
  http.begin(HTTP_URL);
  http.addHeader("Content-Type", "application/json");

  StaticJsonDocument<256> doc;
  doc["devId"]     = DEVICE_ID;
  doc["rid"]       = rid;
  doc["hr"]        = (int)finalHR;
  doc["spo2"]      = (finalSpO2 > 0) ? (int)finalSpO2 : 0;
  doc["temp"]      = serialized(String(finalTemp, 1));
  doc["ambient"]   = serialized(String(finalAmbient, 1));
  if (currentUserId > 0) doc["userId"] = currentUserId;

  char buf[256];
  serializeJson(doc, buf);

  int code = http.POST(buf);
  Serial.print("[HTTP] POST → "); Serial.print(code);
  if (code > 0) {
    Serial.print(" body="); Serial.println(http.getString());
  } else {
    Serial.println(" (lỗi kết nối)");
  }
  http.end();
}

// ═════════════════════════════════════════════
//  Đo HR + SpO2 (giữ nguyên từ code gốc, thêm cancel check)
// ═════════════════════════════════════════════
bool doMeasureHeartRate() {
  Serial.println("\n[GIAI ĐOẠN 1] Đo nhịp tim + SpO2");
  sendBleStatus("WAITING_FINGER");
  Serial.println(">> Mời đặt ngón tay lên cảm biến MAX30102...");

  unsigned long waitStart = millis();
  uint32_t irNow = 0;
  while (true) {
    if (cancelRequested) { sendBleStatus("CANCELLED"); return false; }
    particleSensor.check();
    irNow = particleSensor.getIR();
    if (irNow > FINGER_THRESHOLD) break;
    if (millis() - waitStart > 15000) {
      Serial.print(">> Timeout! IR="); Serial.println(irNow);
      sendBleStatus("TIMEOUT_FINGER");
      return false;
    }
    delay(200);
  }
  Serial.print(">> Phát hiện ngón tay! IR="); Serial.println(irNow);
  sendBleStatus("MEASURING_HR");
  Serial.println(">> Đang đo 5 giây, giữ tay yên...");

  const int SAMPLES = 160;
  uint32_t irBuf[SAMPLES], redBuf[SAMPLES];
  for (int i = 0; i < SAMPLES; i++) {
    if (cancelRequested) { sendBleStatus("CANCELLED"); return false; }
    while (particleSensor.available() == false) particleSensor.check();
    redBuf[i] = particleSensor.getRed();
    irBuf[i]  = particleSensor.getIR();
    particleSensor.nextSample();
  }

  float hrSamples[20], spo2Samples[20];
  int hrCount = 0, spo2Count = 0;
  for (int off = 0; off + 100 <= SAMPLES; off += 6) {
    int32_t s, h; int8_t vs, vh;
    maxim_heart_rate_and_oxygen_saturation(irBuf+off, 100, redBuf+off, &s, &vs, &h, &vh);
    if (vh == 1 && h > 40 && h < 220) hrSamples[hrCount++] = h;
    if (vs == 1 && s > 70 && s <= 100) spo2Samples[spo2Count++] = s;
  }

  if (hrCount < 1) {
    Serial.println(">> Không bắt được nhịp. Thử lại.");
    sendBleStatus("ERROR_HR");
    return false;
  }

  sortArray(hrSamples, hrCount);
  float hrMedian = hrSamples[hrCount / 2];
  finalHR = hrMedian * HR_SCALE;
  if (finalHR < 40 || finalHR > 200) finalHR = hrMedian;

  if (spo2Count >= 1) {
    sortArray(spo2Samples, spo2Count);
    finalSpO2 = spo2Samples[spo2Count / 2];
  } else finalSpO2 = 0;

  Serial.printf("  HR = %d bpm  SpO2 = %s%%\n",
    (int)finalHR,
    finalSpO2 > 0 ? String((int)finalSpO2).c_str() : "--");
  return true;
}

// ═════════════════════════════════════════════
//  Đo nhiệt độ (giữ nguyên từ code gốc, thêm cancel + status)
// ═════════════════════════════════════════════
bool doMeasureTemperature() {
  Serial.println("\n[GIAI ĐOẠN 2] Đo nhiệt độ cơ thể");
  sendBleStatus("WAITING_FOREHEAD");
  Serial.println(">> Mời đưa trán lại gần GY-906 (cách 2-5cm)...");

  finalAmbient = mlx.readAmbientTempC();

  unsigned long waitStart = millis();
  bool detected = false;
  while (millis() - waitStart < 20000) {
    if (cancelRequested) { sendBleStatus("CANCELLED"); return false; }
    float obj = mlx.readObjectTempC();
    if (!isnan(obj) && obj > 28.0 && obj < 42.5 && (obj - finalAmbient) > 1.5) {
      detected = true;
      Serial.printf(">> Phát hiện cơ thể! obj=%.1f C\n", obj);
      break;
    }
    delay(400);
  }

  if (!detected) {
    Serial.println(">> Timeout! Không phát hiện trán.");
    sendBleStatus("TIMEOUT_TEMP");
    finalTemp = 0;
    return false;
  }

  sendBleStatus("MEASURING_TEMP");
  float sum = 0; int valid = 0;
  for (int i = 0; i < 10; i++) {
    if (cancelRequested) { sendBleStatus("CANCELLED"); return false; }
    float t = mlx.readObjectTempC();
    if (!isnan(t) && t > 28.0 && t < 42.5) { sum += t; valid++; }
    delay(300);
  }

  if (valid == 0) { sendBleStatus("ERROR_TEMP"); finalTemp = 0; return false; }

  float rawTemp = sum / valid;
  finalTemp = rawTemp + TEMP_OFFSET;
  Serial.printf("  Nhiệt độ = %.1f C (raw=%.1f + offset=%.1f)\n", finalTemp, rawTemp, TEMP_OFFSET);
  return true;
}

// ═════════════════════════════════════════════
//  Setup
// ═════════════════════════════════════════════
void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("==========================================");
  Serial.println("  ESP32 Healthcare — BLE + MQTT + HTTP");
  Serial.println("==========================================");

  // I2C
  I2C_MAX.begin(MAX_SDA, MAX_SCL, 400000);
  I2C_MLX.begin(MLX_SDA, MLX_SCL, 100000);
  delay(50);

  // MAX30102
  Serial.print("[MAX30102] Khởi tạo... ");
  if (!particleSensor.begin(I2C_MAX, I2C_SPEED_FAST)) {
    Serial.println("FAIL!"); while (1) delay(1000);
  }
  particleSensor.setup(0x3F, 4, 2, 100, 411, 16384);
  particleSensor.setPulseAmplitudeRed(0x3F);
  particleSensor.setPulseAmplitudeIR(0x3F);
  particleSensor.setPulseAmplitudeGreen(0);
  Serial.println("OK");

  // GY-906
  Serial.print("[GY-906]   Khởi tạo... ");
  if (!mlx.begin(0x5A, &I2C_MLX)) {
    Serial.println("FAIL!"); while (1) delay(1000);
  }
  Serial.println("OK");

  // BLE
  setupBLE();

  // WiFi + MQTT
  setupWiFi();
  mqttClient.setServer(MQTT_HOST, MQTT_PORT);
  mqttClient.setCallback(mqttCallback);
  mqttReconnect();

  Serial.println("\n[READY] Chờ lệnh từ app (BLE hoặc MQTT)...\n");
}

// ═════════════════════════════════════════════
//  Loop
// ═════════════════════════════════════════════
void loop() {
  // Giữ MQTT alive
  if (!mqttClient.connected()) mqttReconnect();
  mqttClient.loop();

  if (!measureRequested) {
    delay(50);
    return;
  }

  // ── Bắt đầu chu kỳ đo ──
  // KHOÁ trước, sau đó mới reset measureRequested để mọi gói trùng
  // đến trong khoảng nhỏ này cũng bị processCommand() chặn.
  isMeasuring      = true;
  measureRequested = false;
  cancelRequested  = false;
  finalHR = finalSpO2 = finalTemp = finalAmbient = 0;

  Serial.println("\n======================================");
  Serial.println("  BẮT ĐẦU CHU KỲ ĐO");
  Serial.println("======================================");
  sendBleStatus("MEASURE_START");

  bool hrOk   = doMeasureHeartRate();
  bool tempOk = false;

  if (hrOk) {
    delay(300);
    tempOk = doMeasureTemperature();
  }

  if (!hrOk) {
    Serial.println("[KẾT QUẢ] Đo thất bại ở giai đoạn HR");
    sendBleStatus("MEASURE_FAILED");

    // Drain MQTT trước khi mở khoá (xem giải thích bên dưới)
    unsigned long drainStart = millis();
    while (millis() - drainStart < 1500) {
      mqttClient.loop();
      delay(50);
    }

    isMeasuring = false;
    delay(1500);
    return;
  }

  // ── Tạo rid ──
  String rid = generateRid();
  Serial.print("\n[RID] "); Serial.println(rid);

  // ── In kết quả Serial ──
  Serial.println("\n========== TỔNG KẾT ==========");
  Serial.printf("  Nhịp tim          : %d bpm\n", (int)finalHR);
  Serial.printf("  SpO2              : %s%%\n", finalSpO2 > 0 ? String((int)finalSpO2).c_str() : "--");
  Serial.printf("  Nhiệt độ cơ thể   : %s C\n", tempOk ? String(finalTemp,1).c_str() : "--");
  Serial.printf("  Nhiệt độ môi trường: %.1f C\n", finalAmbient);
  Serial.println("================================");

  sendBleStatus("MEASURE_DONE");

  // ── Gửi 3 kênh đồng thời ──
  sendBleResult(rid);        // BLE notify (nếu app đang kết nối)
  mqttPublishResult(rid);    // MQTT publish (luôn publish, app/server xử lý rid)
  httpPostResult(rid);       // HTTP POST lên server

  // ── DRAIN MQTT QUEUE ──
  // Trong lúc đo (~5-10s), mqttClient.loop() không được gọi → gói MQTT
  // bị broker giữ. Gọi loop() liên tục ~1.5s để xử lý hết các gói còn đọng.
  // Nếu trong đó có gói START_MEASURE trùng (do app gửi cùng lúc với BLE),
  // nó sẽ bị processCommand() chặn vì isMeasuring vẫn = true VÀ
  // debounce MEASURE_DEDUP_MS chưa hết.
  Serial.println("[LOOP] Drain MQTT queue trước khi mở khoá...");
  unsigned long drainStart = millis();
  while (millis() - drainStart < 1500) {
    mqttClient.loop();
    delay(50);
  }

  // Mở khoá — sẵn sàng nhận lệnh đo tiếp theo
  isMeasuring = false;

  Serial.println("\n[READY] Chờ lệnh đo tiếp theo...\n");
  delay(500);
}
