// Copy file này thành config.h rồi điền thông tin thật.
// config.h đã được .gitignore bỏ qua, không bị đẩy lên GitHub.
#pragma once

#define WIFI_SSID       "your-wifi-ssid"
#define WIFI_PASS       "your-wifi-password"

#define MQTT_HOST       "192.168.1.10"      // IP máy chạy Mosquitto
#define MQTT_PORT       1883
#define MQTT_CLIENT_ID  "ESP32_H1"

#define HTTP_URL        "http://192.168.1.10/healthcare/api/esp32_log.php"
#define DEVICE_ID       "ESP32_H1"
