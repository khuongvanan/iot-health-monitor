package com.example.healthmonitor;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.lifecycle.MutableLiveData;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * AppCore.java
 * DataManager + AppHelper + ConnectionState
 *
 * FIX BUG 1: DataManager.clear() — reset LiveData khi logout.
 * MỚI: ConnectionState singleton — persist trạng thái 4 kênh + sensor
 *      qua suốt vòng đời process. Fragment ConnectionStatusFragment
 *      observe ConnectionState để khi rời/quay lại vẫn giữ state.
 */
public class AppCore {

    // =========================================================
    //  DataManager — hàng đợi + điều phối 3 giao thức
    // =========================================================
    public static class DataManager {
        private static final String TAG = "DataManager";
        private static DataManager INSTANCE;

        private final LinkedBlockingQueue<AppModel.SensorData> queue
                = new LinkedBlockingQueue<>(100);

        public final MutableLiveData<AppModel.SensorData> liveData = new MutableLiveData<>();
        public final MutableLiveData<String> statusLiveData = new MutableLiveData<>();

        private ExecutorService executor;
        private Thread  consumerThread;
        private volatile boolean running = false;
        private Context context;

        private long  lastPubTimestamp = 0L;
        private float lastPubHR        = -1f;
        private float lastPubSpo2      = -1f;
        private float lastPubTemp      = -1f;
        private String lastPubDeviceId = "";
        private String lastPubSource   = "";
        private static final long DEDUP_WINDOW_MS = 3000;

        private DataManager() {}

        public static DataManager getInstance() {
            if (INSTANCE == null) INSTANCE = new DataManager();
            return INSTANCE;
        }

        public void init(Context ctx) { this.context = ctx.getApplicationContext(); }

        public void publish(AppModel.SensorData data) {
            if (data == null) return;
            if (context != null) {
                String protocol = data.source != null && !data.source.isEmpty()
                        ? data.source : "UNKNOWN";
                try { AppHelper.getInstance(context).writeLog(protocol, data); }
                catch (Exception ex) { Log.e(TAG, "writeLog error", ex); }
            }
            long now = System.currentTimeMillis();
            String devId = data.deviceId != null ? data.deviceId : "";
            String src   = data.source   != null ? data.source   : "";
            String lastKey = lastPubDeviceId + "|" + lastPubSource;
            String thisKey = devId + "|" + src;

            if (now - lastPubTimestamp < DEDUP_WINDOW_MS
                    && thisKey.equals(lastKey)
                    && Math.abs(lastPubHR   - data.heartRate)   < 0.5f
                    && Math.abs(lastPubSpo2 - data.spo2)        < 0.5f
                    && Math.abs(lastPubTemp - data.temperature) < 0.1f) {
                Log.d(TAG, "Dedup same-source: bỏ qua bản trùng từ " + src);
                return;
            }
            lastPubTimestamp = now;
            lastPubHR        = data.heartRate;
            lastPubSpo2      = data.spo2;
            lastPubTemp      = data.temperature;
            lastPubDeviceId  = devId;
            lastPubSource    = src;

            if (context != null && (data.userId == null || data.userId.equals("0"))) {
                data.userId = AppHelper.getInstance(context).getUserId();
            }
            queue.offer(data);
            liveData.postValue(data);
        }

        public void publishStatus(String message) { statusLiveData.postValue(message); }

        public void startConsumer() {
            if (running) return;
            running = true;
            executor = Executors.newFixedThreadPool(3);
            consumerThread = new Thread(() -> {
                Log.d(TAG, "Consumer started");
                while (running && !Thread.currentThread().isInterrupted()) {
                    try {
                        AppModel.SensorData data = queue.poll(1, TimeUnit.SECONDS);
                        if (data != null) dispatch(data);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "DataConsumer");
            consumerThread.setDaemon(true);
            consumerThread.start();
        }

        private void dispatch(AppModel.SensorData data) {
            executor.execute(() -> {
                try {
                    AppDatabase.SensorLogEntity e = new AppDatabase.SensorLogEntity();
                    e.heartRate   = data.heartRate;
                    e.spo2        = data.spo2;
                    e.temperature = data.temperature;
                    e.deviceId    = data.deviceId != null ? data.deviceId : "";
                    e.source      = data.source   != null ? data.source   : "";
                    e.timestamp   = data.timestamp;
                    e.synced      = false;
                    try { e.userId = Integer.parseInt(data.userId); } catch (Exception ex) { e.userId = 0; }
                    AppDatabase.AppRoomDatabase.getInstance(context).appDao().insertLog(e);
                } catch (Exception ex) {
                    Log.e(TAG, "Room error", ex);
                }
            });
            executor.execute(() -> {
                int[] delaysMs = {1000, 2000, 3000};
                for (int i = 0; i < 3; i++) {
                    try {
                        retrofit2.Response<okhttp3.ResponseBody> resp =
                                AppNetwork.RetrofitHelper.getInstance().logSensor(data).execute();
                        Log.d(TAG, "HTTP POST sensor_log.php source=" + data.source
                                + " code=" + resp.code()
                                + (resp.isSuccessful() ? " OK" : " FAIL"));
                        if (resp.isSuccessful()) return;
                    } catch (IOException ex) {
                        Log.w(TAG, "HTTP retry " + (i + 1) + " err=" + ex.getMessage());
                    }
                    try { Thread.sleep(delaysMs[i]); } catch (InterruptedException ie) { return; }
                }
            });
        }

        public void stopConsumer() {
            running = false;
            if (consumerThread != null) consumerThread.interrupt();
            if (executor != null) executor.shutdown();
        }

        /** Reset state trong RAM khi logout (FIX BUG 1). */
        public void clear() {
            Log.d(TAG, "Clear: reset LiveData + dedup state");
            queue.clear();
            liveData.postValue(null);
            statusLiveData.postValue(null);
            lastPubTimestamp = 0L;
            lastPubHR = -1f; lastPubSpo2 = -1f; lastPubTemp = -1f;
            lastPubDeviceId = ""; lastPubSource = "";
        }
    }

    // =========================================================
    //  ConnectionState — singleton lưu trạng thái 4 kênh + sensor
    //  MỚI: giải quyết vấn đề mất state khi rời/quay lại
    //       ConnectionStatusFragment.
    //
    //  Cập nhật từ:
    //    - MqttHelper.parseMessage khi nhận SENSOR_STATUS
    //    - MonitoringService khi auto-check 15 phút
    //    - ConnectionStatusFragment khi user nhấn quét
    //    - BleListener khi BLE connect/disconnect
    // =========================================================
    public static class ConnectionState {
        public enum Status { CONNECTED, CONNECTING, DISCONNECTED, ERROR, UNKNOWN }
        private static ConnectionState INSTANCE;

        public final MutableLiveData<Status> bleState    = new MutableLiveData<>(Status.UNKNOWN);
        public final MutableLiveData<Status> mqttState   = new MutableLiveData<>(Status.UNKNOWN);
        public final MutableLiveData<Status> serverState = new MutableLiveData<>(Status.UNKNOWN);
        public final MutableLiveData<Status> espState    = new MutableLiveData<>(Status.UNKNOWN);

        // Thời điểm state thay đổi gần nhất (ms epoch). 0 = chưa từng update.
        public volatile long timeBle    = 0L;
        public volatile long timeMqtt   = 0L;
        public volatile long timeServer = 0L;
        public volatile long timeEsp    = 0L;

        // Sensor status từ ESP32 — "--" / "OK" / "ERR"
        public final MutableLiveData<String> sensorMax = new MutableLiveData<>("--");
        public final MutableLiveData<String> sensorMlx = new MutableLiveData<>("--");
        public volatile long lastSensorCheckMs = 0L;

        // Cờ đang trong tiến trình check (cho fragment biết để show progress)
        public final MutableLiveData<Boolean> checking = new MutableLiveData<>(false);

        private ConnectionState() {}

        public static ConnectionState getInstance() {
            if (INSTANCE == null) INSTANCE = new ConnectionState();
            return INSTANCE;
        }

        public void setBle(Status s) {
            if (s != bleState.getValue()) {
                timeBle = System.currentTimeMillis();
                bleState.postValue(s);
            }
        }
        public void setMqtt(Status s) {
            if (s != mqttState.getValue()) {
                timeMqtt = System.currentTimeMillis();
                mqttState.postValue(s);
            }
        }
        public void setServer(Status s) {
            if (s != serverState.getValue()) {
                timeServer = System.currentTimeMillis();
                serverState.postValue(s);
            }
        }
        public void setEsp(Status s) {
            if (s != espState.getValue()) {
                timeEsp = System.currentTimeMillis();
                espState.postValue(s);
            }
        }

        /** ESP32 trả về SENSOR_STATUS → cập nhật trạng thái cảm biến. */
        public void setSensors(String max, String mlx) {
            sensorMax.postValue(max != null ? max : "--");
            sensorMlx.postValue(mlx != null ? mlx : "--");
            lastSensorCheckMs = System.currentTimeMillis();
            // Có sensor status từ ESP → ESP đang sống
            setEsp(Status.CONNECTED);
        }

        public void setChecking(boolean v) { checking.postValue(v); }
    }

    // =========================================================
    //  AppHelper — SharedPreferences + File log
    // =========================================================
    public static class AppHelper {
        private static final String PREFS_NAME = "healthcare_prefs";
        private static AppHelper INSTANCE;
        private final SharedPreferences prefs;
        private final Context context;

        private AppHelper(Context ctx) {
            context = ctx.getApplicationContext();
            prefs   = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }

        public static AppHelper getInstance(Context context) {
            if (INSTANCE == null) INSTANCE = new AppHelper(context);
            return INSTANCE;
        }

        public void  saveToken(String v) { edit().putString("token", v).apply(); }
        public String getToken()         { return prefs.getString("token", ""); }
        public void  saveRole(String v)  { edit().putString("role", v).apply(); }
        public String getRole()          { return prefs.getString("role", "guest"); }
        public void  saveUserId(String v){ edit().putString("userId", v).apply(); }
        public String getUserId()        { return prefs.getString("userId", "0"); }
        public int   getUserIdInt()      {
            try { return Integer.parseInt(getUserId()); } catch (Exception e) { return 0; }
        }
        public void  saveDeviceId(String v){ edit().putString("deviceId", v).apply(); }
        public String getDeviceId()        { return prefs.getString("deviceId", "Chưa kết nối"); }
        public void    saveServiceState(boolean v){ edit().putBoolean("serviceOn", v).apply(); }
        public boolean getServiceState()          { return prefs.getBoolean("serviceOn", false); }
        public void  saveLastHR(float v)  { edit().putFloat("lastHR", v).apply(); }
        public float getLastHR()          { return prefs.getFloat("lastHR", 0f); }
        public void  saveLastSpo2(float v){ edit().putFloat("lastSpo2", v).apply(); }
        public float getLastSpo2()        { return prefs.getFloat("lastSpo2", 0f); }
        public void  saveLastTemp(float v){ edit().putFloat("lastTemp", v).apply(); }
        public float getLastTemp()        { return prefs.getFloat("lastTemp", 0f); }
        public void saveLastSyncTime(long t){ edit().putLong("lastSync", t).apply(); }
        public long getLastSyncTime()       { return prefs.getLong("lastSync", 0); }
        public boolean isSessionValid() { return !getToken().isEmpty(); }
        public void clearAll() { prefs.edit().clear().apply(); }

        public void writeLog(String protocol, AppModel.SensorData data) {
            try {
                File logFile = new File(context.getFilesDir(),
                        protocol.toLowerCase(Locale.ROOT) + "_log.txt");
                if (logFile.exists() && logFile.length() > 5L * 1024 * 1024) logFile.delete();
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(new Date(data.timestamp));
                String line = ts + " [" + protocol + "] hr=" + data.heartRate
                        + " spo2=" + data.spo2 + " temp=" + data.temperature
                        + " dev=" + data.deviceId + "\n";
                try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                    fos.write(line.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                Log.e("AppHelper", "writeLog error", e);
            }
        }

        private SharedPreferences.Editor edit() { return prefs.edit(); }
    }
}