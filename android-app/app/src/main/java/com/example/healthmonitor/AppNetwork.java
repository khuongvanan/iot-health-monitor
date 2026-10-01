package com.example.healthmonitor;
import android.annotation.SuppressLint;
import retrofit2.Call;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.*;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.*;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.*;

/**
 * FIX BUG 2: MqttHelper có queue pendingCmds + state connecting + isConnected().
 * MỚI: BleHelper.isConnected().
 * MỚI: MqttHelper.parseMessage tự nhận biết code=SENSOR_STATUS và cập nhật
 *      ConnectionState (trạng thái cảm biến MAX30102 + MLX90614).
 * THÊM: confirmMedicine() trong ApiService.
 */
public class AppNetwork {

    // =========================================================
    //  HTTP / RETROFIT
    // =========================================================
    public interface ApiService {
        @POST("login.php")
        Call<AppModel.LoginResponse> login(@Body AppModel.LoginRequest request);
        @POST("users_create.php")
        Call<AppModel.User> createUser(@Body AppModel.User user);
        @PUT("users_changepass.php")
        Call<okhttp3.ResponseBody> changePassword(@Body Map<String, String> body);
        @GET("users_list.php")
        Call<List<AppModel.User>> listUsers();
        @DELETE("users_delete.php")
        Call<okhttp3.ResponseBody> deleteUser(@Query("id") int id);
        @POST("devices_register.php")
        Call<AppModel.Device> registerDevice(@Body AppModel.Device device);
        @DELETE("devices_unregister.php")
        Call<okhttp3.ResponseBody> unregisterDevice(@Query("deviceId") String deviceId);
        @GET("devices_verify.php")
        Call<Boolean> verifyDevice(@Query("deviceId") String deviceId);
        @GET("devices_my.php")
        Call<List<AppModel.Device>> getMyDevices();
        @POST("sensor_log.php")
        Call<okhttp3.ResponseBody> logSensor(@Body AppModel.SensorData data);
        @GET("sensor_history.php")
        Call<List<AppModel.SensorData>> getSensorHistory(
                @Query("userId") int userId, @Query("range") String range);
        @POST("medicine_save.php")
        Call<AppModel.MedicineSchedule> saveMedicine(@Body AppModel.MedicineSchedule schedule);
        @GET("medicine_list.php")
        Call<List<AppModel.MedicineSchedule>> getMedicineList(@Query("userId") int userId);
        @DELETE("medicine_delete.php")
        Call<okhttp3.ResponseBody> deleteMedicine(@Query("id") int id);

        // ── THÊM MỚI: xác nhận đã uống / bỏ qua ──────────────
        @POST("medicine_confirm.php")
        Call<okhttp3.ResponseBody> confirmMedicine(@Body AppModel.ConfirmRequest request);
    }

    public static class RetrofitHelper {
        private static final String BASE_URL = "http://192.168.1.10/healthcare/api/";
        private static ApiService INSTANCE;
        private static String currentToken = "";

        public static void setToken(String token) {
            currentToken = token != null ? token : "";
            INSTANCE = null;
        }

        public static ApiService getInstance() {
            if (INSTANCE == null) {
                HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
                logging.setLevel(HttpLoggingInterceptor.Level.BODY);
                OkHttpClient client = new OkHttpClient.Builder()
                        .addInterceptor(chain -> {
                            Request req = chain.request().newBuilder()
                                    .header("Authorization", "Bearer " + currentToken)
                                    .header("Content-Type", "application/json")
                                    .build();
                            return chain.proceed(req);
                        })
                        .addInterceptor(logging)
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build();
                INSTANCE = new Retrofit.Builder()
                        .baseUrl(BASE_URL)
                        .client(client)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()
                        .create(ApiService.class);
            }
            return INSTANCE;
        }
    }

    // =========================================================
    //  BLE
    // =========================================================
    public interface BleListener {
        void onDeviceFound(BluetoothDevice device);
        void onConnected(String deviceId);
        void onDataReceived(AppModel.SensorData data);
        void onStatusReceived(String code, String message);
        void onDisconnected();
        void onError(String message);
        void onDeviceIdRead(String deviceId);
    }

    public static class BleHelper {
        private static final String TAG          = "BleHelper";
        private static final String UUID_SERVICE     = "12345678-1234-1234-1234-123456789abc";
        private static final String UUID_CHAR_CMD    = "12345678-1234-1234-1234-123456789001";
        private static final String UUID_CHAR_DATA   = "12345678-1234-1234-1234-123456789002";
        private static final String UUID_CHAR_STATUS = "12345678-1234-1234-1234-123456789003";
        private static final String UUID_CCCD    = "00002902-0000-1000-8000-00805f9b34fb";
        private static final String TARGET_NAME  = "ESP32_H1";

        private static BleHelper INSTANCE;
        private final Context   context;
        private BluetoothAdapter btAdapter;
        private BluetoothLeScanner scanner;
        private ScanCallback    scanCallback;
        private BluetoothGatt   gatt;
        private BleListener     listener;
        private final Handler   mainHandler = new Handler(Looper.getMainLooper());
        private boolean connectedFired = false;

        private BleHelper(Context ctx) {
            context = ctx.getApplicationContext();
            BluetoothManager mgr = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (mgr != null) btAdapter = mgr.getAdapter();
        }

        public static BleHelper getInstance(Context context) {
            if (INSTANCE == null) INSTANCE = new BleHelper(context);
            return INSTANCE;
        }

        public void setListener(BleListener l) { listener = l; }

        public boolean isConnected() { return gatt != null && connectedFired; }

        @SuppressLint("MissingPermission")
        @androidx.annotation.RequiresPermission(allOf = {
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT
        })
        public void scanDevices() {
            if (btAdapter == null || !btAdapter.isEnabled()) {
                if (listener != null) listener.onError("Bluetooth chưa bật");
                return;
            }
            scanner = btAdapter.getBluetoothLeScanner();
            if (scanner == null) {
                if (listener != null) listener.onError("Không lấy được BLE scanner");
                return;
            }
            final boolean[] foundTarget = {false};
            scanCallback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    BluetoothDevice device = result.getDevice();
                    if (device == null || foundTarget[0]) return;
                    String name;
                    try { name = device.getName(); } catch (SecurityException e) { return; }
                    if (name != null && name.equals(TARGET_NAME)) {
                        foundTarget[0] = true;
                        stopScan();
                        if (listener != null) listener.onDeviceFound(device);
                    }
                }
                @Override
                public void onScanFailed(int errorCode) {
                    if (listener != null) listener.onError("Scan failed: " + errorCode);
                }
            };
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(null, settings, scanCallback);
            mainHandler.postDelayed(() -> {
                if (!foundTarget[0]) {
                    stopScan();
                    if (listener != null) listener.onError("Không tìm thấy " + TARGET_NAME);
                }
            }, 10000);
        }

        @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN)
        private void stopScan() {
            if (scanner != null && scanCallback != null) {
                try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
            }
        }

        @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        public void connect(BluetoothDevice device) {
            if (gatt != null) {
                try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
                gatt = null;
            }
            connectedFired = false;
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        }

        @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        public void disconnect() {
            if (gatt != null) { gatt.disconnect(); gatt.close(); gatt = null; }
            connectedFired = false;
        }

        @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        public boolean unpairDevice() {
            if (gatt != null) {
                try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
                gatt = null;
            }
            connectedFired = false;
            BluetoothManager mgr = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (mgr == null || mgr.getAdapter() == null) return false;
            try {
                Set<BluetoothDevice> bonded = mgr.getAdapter().getBondedDevices();
                if (bonded == null) return true;
                for (BluetoothDevice device : bonded) {
                    String name;
                    try { name = device.getName(); } catch (SecurityException e) { continue; }
                    if (TARGET_NAME.equals(name)) {
                        try {
                            java.lang.reflect.Method removeBond = device.getClass().getMethod("removeBond");
                            Object resultObj = removeBond.invoke(device);
                            boolean result = resultObj != null && (boolean) resultObj;
                            Log.d(TAG, "unpairDevice: removeBond result=" + result);
                            return result;
                        } catch (Exception e) {
                            Log.e(TAG, "unpairDevice: reflection error", e);
                            return false;
                        }
                    }
                }
            } catch (SecurityException e) {
                Log.e(TAG, "unpairDevice: permission error", e);
                return false;
            }
            return true;
        }

        @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        public void writeCommand(String command) {
            if (gatt == null) {
                if (listener != null) listener.onError("Chưa kết nối thiết bị");
                return;
            }
            BluetoothGattService service = gatt.getService(UUID.fromString(UUID_SERVICE));
            if (service == null) return;
            BluetoothGattCharacteristic ch = service.getCharacteristic(UUID.fromString(UUID_CHAR_CMD));
            if (ch == null) return;
            ch.setValue(command.getBytes(StandardCharsets.UTF_8));
            ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            gatt.writeCharacteristic(ch);
        }

        private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
            @Override
            @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
            public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "BLE connected, requesting MTU 247...");
                    boolean ok = g.requestMtu(247);
                    if (!ok) {
                        Log.w(TAG, "requestMtu failed, fallback to discoverServices");
                        g.discoverServices();
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedFired = false;
                    AppCore.ConnectionState.getInstance()
                            .setBle(AppCore.ConnectionState.Status.DISCONNECTED);
                    if (listener != null) listener.onDisconnected();
                }
            }
            @Override
            @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
            public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
                Log.d(TAG, "BLE MTU=" + mtu + " status=" + status);
                g.discoverServices();
            }
            @Override
            @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) return;
                BluetoothGattService service = g.getService(UUID.fromString(UUID_SERVICE));
                if (service == null) {
                    if (listener != null) listener.onError("Service UUID không tìm thấy");
                    return;
                }
                BluetoothGattCharacteristic chData = service.getCharacteristic(UUID.fromString(UUID_CHAR_DATA));
                if (chData != null) {
                    g.setCharacteristicNotification(chData, true);
                    BluetoothGattDescriptor descData = chData.getDescriptor(UUID.fromString(UUID_CCCD));
                    if (descData != null) {
                        descData.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        g.writeDescriptor(descData);
                    }
                }
                BluetoothGattCharacteristic chStatus = service.getCharacteristic(UUID.fromString(UUID_CHAR_STATUS));
                if (chStatus != null) {
                    g.setCharacteristicNotification(chStatus, true);
                    BluetoothGattDescriptor descStatus = chStatus.getDescriptor(UUID.fromString(UUID_CCCD));
                    if (descStatus != null) {
                        descStatus.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        g.writeDescriptor(descStatus);
                    }
                }
                mainHandler.postDelayed(() -> {
                    if (!connectedFired && listener != null) {
                        connectedFired = true;
                        AppCore.ConnectionState.getInstance()
                                .setBle(AppCore.ConnectionState.Status.CONNECTED);
                        listener.onConnected(TARGET_NAME);
                    }
                }, 1500);
            }
            @Override
            public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor desc, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS && !connectedFired) {
                    connectedFired = true;
                    AppCore.ConnectionState.getInstance()
                            .setBle(AppCore.ConnectionState.Status.CONNECTED);
                    if (listener != null) {
                        mainHandler.post(() -> listener.onConnected(TARGET_NAME));
                    }
                }
            }
            @Override
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
                String uuid = ch.getUuid().toString();
                byte[] raw = ch.getValue();
                String content = (raw != null) ? new String(raw, StandardCharsets.UTF_8) : "";
                Log.d(TAG, "BLE notify uuid=" + uuid + " content=" + content);
                boolean isJson = !content.isEmpty() && content.charAt(0) == '{';
                if (isJson) {
                    parseAndNotify(raw);
                } else {
                    if (listener != null) listener.onStatusReceived(content, content);
                }
            }
            private void parseAndNotify(byte[] raw) {
                if (raw == null || raw.length == 0) return;
                try {
                    String json = new String(raw, StandardCharsets.UTF_8);
                    JsonObject obj = new Gson().fromJson(json, JsonObject.class);
                    String devId = obj.has("devId") ? obj.get("devId").getAsString() : TARGET_NAME;

                    if (obj.has("code") && "SENSOR_STATUS".equals(obj.get("code").getAsString())) {
                        String max = obj.has("max30102") ? obj.get("max30102").getAsString() : "--";
                        String mlx = obj.has("mlx90614") ? obj.get("mlx90614").getAsString() : "--";
                        AppCore.ConnectionState.getInstance().setSensors(max, mlx);
                        return;
                    }
                    if (!obj.has("hr") && listener != null) {
                        listener.onDeviceIdRead(devId);
                        return;
                    }
                    AppModel.SensorData data = new AppModel.SensorData();
                    data.heartRate   = obj.has("hr")   ? obj.get("hr").getAsFloat()   : 0;
                    data.spo2        = obj.has("spo2") ? obj.get("spo2").getAsFloat() : 0;
                    data.temperature = obj.has("temp") ? obj.get("temp").getAsFloat() : 0;
                    data.deviceId    = devId;
                    data.source      = "BLE";
                    data.timestamp   = System.currentTimeMillis();
                    if (listener != null) listener.onDataReceived(data);
                } catch (Exception e) {
                    Log.e(TAG, "BLE parse error", e);
                }
            }
        };
    }

    // =========================================================
    //  MQTT
    // =========================================================
    public interface MqttListener {
        void onConnected();
        void onMessageReceived(AppModel.SensorData data, String topic);
        void onStatusReceived(String code, String message);
        void onDisconnected();
        void onError(String message);
    }

    public static class MqttHelper {
        private static final String TAG = "MqttHelper";
        private static final String BROKER       = "tcp://192.168.1.10:1883";
        private static final String TOPIC_DATA   = "sensor/data";
        private static final String TOPIC_STATUS = "sensor/status";
        private static final String TOPIC_CMD    = "sensor/cmd";

        private static MqttHelper INSTANCE;
        private MqttClient   client;
        private MqttListener listener;

        private final AtomicBoolean connecting = new AtomicBoolean(false);
        private final Queue<String> pendingCmds = new ConcurrentLinkedQueue<>();

        private MqttHelper() {}

        public static MqttHelper getInstance() {
            if (INSTANCE == null) INSTANCE = new MqttHelper();
            return INSTANCE;
        }

        public void setListener(MqttListener l) { listener = l; }

        public boolean isConnected() { return client != null && client.isConnected(); }

        public void connect() {
            if (isConnected() || connecting.get()) return;
            connecting.set(true);

            AppCore.ConnectionState.getInstance()
                    .setMqtt(AppCore.ConnectionState.Status.CONNECTING);

            new Thread(() -> {
                try {
                    String clientId = "AndroidApp_" + System.currentTimeMillis();
                    client = new MqttClient(BROKER, clientId, new MemoryPersistence());
                    MqttConnectOptions opts = new MqttConnectOptions();
                    opts.setCleanSession(true);
                    opts.setKeepAliveInterval(30);
                    opts.setConnectionTimeout(15);
                    opts.setAutomaticReconnect(true);

                    client.setCallback(new MqttCallback() {
                        @Override public void connectionLost(Throwable cause) {
                            Log.w(TAG, "MQTT lost: " + (cause != null ? cause.getMessage() : "null"));
                            AppCore.ConnectionState.getInstance()
                                    .setMqtt(AppCore.ConnectionState.Status.DISCONNECTED);
                            if (listener != null) listener.onDisconnected();
                        }
                        @Override public void messageArrived(String topic, MqttMessage msg) {
                            parseMessage(topic, msg.getPayload());
                        }
                        @Override public void deliveryComplete(IMqttDeliveryToken token) {}
                    });

                    client.connect(opts);
                    client.subscribe(new String[]{TOPIC_DATA, TOPIC_STATUS}, new int[]{1, 1});
                    connecting.set(false);
                    Log.d(TAG, "MQTT connected, subscribed to data + status");

                    AppCore.ConnectionState.getInstance()
                            .setMqtt(AppCore.ConnectionState.Status.CONNECTED);
                    if (listener != null) listener.onConnected();

                    String c;
                    while ((c = pendingCmds.poll()) != null) {
                        Log.d(TAG, "Flush pending cmd: " + c);
                        doPublish(c);
                    }
                } catch (MqttException e) {
                    connecting.set(false);
                    Log.e(TAG, "MQTT connect error", e);
                    AppCore.ConnectionState.getInstance()
                            .setMqtt(AppCore.ConnectionState.Status.ERROR);
                    if (listener != null) listener.onError(e.getMessage());
                }
            }, "MQTT-Connect").start();
        }

        public boolean publishCommand(String command) {
            if (isConnected()) {
                new Thread(() -> doPublish(command), "MQTT-Cmd").start();
                return true;
            }
            Log.w(TAG, "MQTT not ready, queue cmd: " + command);
            pendingCmds.offer(command);
            connect();
            return false;
        }

        private void doPublish(String command) {
            try {
                MqttMessage msg = new MqttMessage(command.getBytes(StandardCharsets.UTF_8));
                msg.setQos(1);
                client.publish(TOPIC_CMD, msg);
                Log.d(TAG, "Published command: " + command);
            } catch (MqttException e) {
                Log.e(TAG, "Publish error, re-queue: " + command, e);
                pendingCmds.offer(command);
            } catch (Exception e) {
                Log.e(TAG, "Publish unexpected error", e);
            }
        }

        public void disconnect() {
            pendingCmds.clear();
            AppCore.ConnectionState.getInstance()
                    .setMqtt(AppCore.ConnectionState.Status.DISCONNECTED);
            new Thread(() -> {
                try {
                    if (client != null && client.isConnected()) client.disconnect(1000);
                } catch (MqttException e) {
                    Log.e(TAG, "MQTT disconnect error");
                }
            }).start();
        }

        private void parseMessage(String topic, byte[] payload) {
            try {
                String json = new String(payload, StandardCharsets.UTF_8);
                JsonObject obj = new Gson().fromJson(json, JsonObject.class);

                if (TOPIC_STATUS.equals(topic)) {
                    String code = obj.has("code") ? obj.get("code").getAsString() : "";
                    String msg  = obj.has("msg")  ? obj.get("msg").getAsString()  : "";
                    Log.d(TAG, "Status [" + code + "]: " + msg);

                    if ("SENSOR_STATUS".equals(code)) {
                        String max = obj.has("max30102") ? obj.get("max30102").getAsString() : "--";
                        String mlx = obj.has("mlx90614") ? obj.get("mlx90614").getAsString() : "--";
                        Log.d(TAG, "Sensor status: MAX=" + max + " MLX=" + mlx);
                        AppCore.ConnectionState.getInstance().setSensors(max, mlx);
                        return;
                    }

                    AppCore.ConnectionState.getInstance()
                            .setEsp(AppCore.ConnectionState.Status.CONNECTED);
                    if (listener != null) listener.onStatusReceived(code, msg);
                } else if (TOPIC_DATA.equals(topic)) {
                    AppModel.SensorData data = new AppModel.SensorData();
                    data.heartRate   = obj.has("hr")   ? obj.get("hr").getAsFloat()   : 0;
                    data.spo2        = obj.has("spo2") ? obj.get("spo2").getAsFloat() : 0;
                    data.temperature = obj.has("temp") ? obj.get("temp").getAsFloat() : 0;
                    data.deviceId    = obj.has("devId")? obj.get("devId").getAsString(): "";
                    data.source      = "MQTT";
                    data.timestamp   = System.currentTimeMillis();
                    AppCore.ConnectionState.getInstance()
                            .setEsp(AppCore.ConnectionState.Status.CONNECTED);
                    if (listener != null) listener.onMessageReceived(data, topic);
                }
            } catch (Exception e) {
                Log.e(TAG, "MQTT parse error", e);
            }
        }
    }
}