package com.example.healthmonitor;

import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.*;
import android.text.TextUtils;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.work.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * AppService — Service + Alarm + SyncWorker.
 *
 * NÂNG CẤP AlarmHelper:
 *  - repeatDaily: đặt lại alarm ngày hôm sau sau khi xác nhận
 *  - Snooze 5 phút: dùng requestCode = schedule.id * 10000 + snoozeIndex
 *    để tránh đè lên alarm gốc
 *  - cancelAllSnooze(context, id): huỷ tất cả snooze pending khi guest xác nhận
 *
 * NÂNG CẤP MedicineAlarmReceiver:
 *  - Phát âm thanh báo thức 15s (TYPE_ALARM) và rung
 *  - Sau 15s: tắt âm, gửi notification với action "Đã uống" / "Bỏ qua"
 *  - Đặt snooze alarm 5 phút sau (nếu chưa xác nhận)
 *  - SharedPrefs "pending_confirms" lưu set scheduleId đang chờ confirm
 */
public class AppService {

    // =========================================================
    //  MonitoringService — giữ nguyên, chỉ thêm phần BLE
    // =========================================================
    public static class MonitoringService extends Service {
        private static final String TAG        = "MonitoringService";
        private static final String CHANNEL_ID = "health_monitor";
        private static final int    NOTIF_ID   = 1001;

        private final Handler reconnectHandler  = new Handler(Looper.getMainLooper());
        private final Handler healthCheckHandler = new Handler(Looper.getMainLooper());
        private static final long BLE_RECONNECT_DELAY_MS      = 5000;
        private static final long HEALTH_CHECK_INTERVAL_MS    = 15 * 60 * 1000L;
        private static final long HEALTH_CHECK_INITIAL_DELAY_MS = 60 * 1000L;
        private volatile boolean bleStarted = false;
        private Runnable healthCheckRunnable;

        @SuppressLint("MissingPermission")
        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            Log.d(TAG, "Service starting...");
            createNotificationChannel();
            startForeground(NOTIF_ID, buildNotification("Đang theo dõi sức khỏe", "--", "--", "--"));

            AppCore.DataManager.getInstance().init(this);
            AppCore.DataManager.getInstance().startConsumer();

            AppNetwork.MqttHelper mqtt = AppNetwork.MqttHelper.getInstance();
            mqtt.setListener(new AppNetwork.MqttListener() {
                @Override public void onConnected() { Log.d(TAG, "MQTT ready in service"); }
                @Override public void onMessageReceived(AppModel.SensorData data, String topic) {
                    verifyAndProcess(data);
                }
                @Override public void onStatusReceived(String code, String message) {
                    AppCore.DataManager.getInstance().publishStatus(message);
                }
                @Override public void onDisconnected() { Log.w(TAG, "MQTT lost"); }
                @Override public void onError(String msg) { Log.e(TAG, "MQTT error: " + msg); }
            });
            mqtt.connect();
            startBleIfNeeded();
            // FIX: thử lấy lịch thuốc MỚI NHẤT từ server trước, có mạng thì ghi đè local;
            // mất mạng thì tự fallback dùng lịch local gần nhất để đặt alarm (giữ hành vi offline cũ)
            MedicineLocalSync.refreshFromServerThenSchedule(this);
            scheduleHealthCheck();
            return START_STICKY;
        }

        @SuppressLint("MissingPermission")
        @androidx.annotation.RequiresPermission(allOf = {
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT
        })
        private void startBleIfNeeded() {
            if (bleStarted) return;
            AppCore.AppHelper helper = AppCore.AppHelper.getInstance(this);
            String savedDeviceId = helper.getDeviceId();
            if (TextUtils.isEmpty(savedDeviceId) || "Chưa kết nối".equals(savedDeviceId)) return;

            android.bluetooth.BluetoothManager btMgr =
                    (android.bluetooth.BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            if (btMgr == null || btMgr.getAdapter() == null || !btMgr.getAdapter().isEnabled()) return;
            bleStarted = true;

            AppNetwork.BleHelper ble = AppNetwork.BleHelper.getInstance(this);
            ble.setListener(new AppNetwork.BleListener() {
                @SuppressLint("MissingPermission")
                @Override
                public void onDeviceFound(BluetoothDevice device) { ble.connect(device); }
                @Override public void onConnected(String deviceId) { Log.d(TAG, "BLE: " + deviceId); }
                @Override public void onDataReceived(AppModel.SensorData data) {
                    data.source = "BLE"; verifyAndProcess(data);
                }
                @Override public void onStatusReceived(String code, String message) {
                    AppCore.DataManager.getInstance().publishStatus(message);
                }
                @Override public void onDisconnected() {
                    bleStarted = false;
                    reconnectHandler.postDelayed(this::tryReconnect, BLE_RECONNECT_DELAY_MS);
                }
                @Override public void onError(String message) {
                    bleStarted = false;
                    reconnectHandler.postDelayed(this::tryReconnect, BLE_RECONNECT_DELAY_MS);
                }
                @Override public void onDeviceIdRead(String deviceId) {}

                @SuppressLint("MissingPermission")
                private void tryReconnect() { startBleIfNeeded(); }
            });

            BluetoothDevice pairedTarget = null;
            try {
                Set<BluetoothDevice> bonded = btMgr.getAdapter().getBondedDevices();
                if (bonded != null) {
                    for (BluetoothDevice d : bonded) {
                        if (savedDeviceId.equals(d.getName())) { pairedTarget = d; break; }
                    }
                }
            } catch (SecurityException e) { Log.e(TAG, "BLE paired list error", e); }

            if (pairedTarget != null) {
                try { ble.connect(pairedTarget); }
                catch (SecurityException e) { bleStarted = false; }
            } else {
                ble.scanDevices();
            }
        }

        private void scheduleHealthCheck() {
            healthCheckRunnable = new Runnable() {
                @Override public void run() {
                    performHealthCheck("auto");
                    healthCheckHandler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS);
                }
            };
            healthCheckHandler.postDelayed(healthCheckRunnable, HEALTH_CHECK_INITIAL_DELAY_MS);
        }

        @SuppressLint("MissingPermission")
        public void performHealthCheck(String trigger) {
            AppCore.ConnectionState cs = AppCore.ConnectionState.getInstance();
            cs.setChecking(true);

            // FIX: nhân tiện health check (mỗi 15 phút) thử làm mới lịch thuốc từ server luôn,
            // để nếu admin đổi giờ trong lúc app đang chạy offline thì khi có mạng lại
            // sẽ tự bắt được thay đổi mà không cần user mở tab thuốc.
            MedicineLocalSync.refreshFromServerThenSchedule(this);
            if (AppNetwork.MqttHelper.getInstance().isConnected()) {
                cs.setMqtt(AppCore.ConnectionState.Status.CONNECTED);
            } else {
                cs.setMqtt(AppCore.ConnectionState.Status.DISCONNECTED);
                AppNetwork.MqttHelper.getInstance().connect();
            }
            if (AppNetwork.BleHelper.getInstance(this).isConnected()) {
                cs.setBle(AppCore.ConnectionState.Status.CONNECTED);
            } else {
                cs.setBle(AppCore.ConnectionState.Status.DISCONNECTED);
            }
            cs.setServer(AppCore.ConnectionState.Status.CONNECTING);
            AppNetwork.RetrofitHelper.getInstance().getMyDevices()
                    .enqueue(new retrofit2.Callback<List<AppModel.Device>>() {
                        @Override public void onResponse(retrofit2.Call<List<AppModel.Device>> call,
                                                         retrofit2.Response<List<AppModel.Device>> response) {
                            cs.setServer(response.isSuccessful() || response.code() == 401
                                    ? AppCore.ConnectionState.Status.CONNECTED
                                    : AppCore.ConnectionState.Status.ERROR);
                        }
                        @Override public void onFailure(retrofit2.Call<List<AppModel.Device>> call, Throwable t) {
                            cs.setServer(AppCore.ConnectionState.Status.DISCONNECTED);
                        }
                    });
            cs.setEsp(AppCore.ConnectionState.Status.CONNECTING);
            String cmd = "{\"cmd\":\"STATUS_CHECK\"}";
            AppNetwork.MqttHelper.getInstance().publishCommand(cmd);
            if (AppNetwork.BleHelper.getInstance(this).isConnected()) {
                try { AppNetwork.BleHelper.getInstance(this).writeCommand(cmd); }
                catch (Exception ignored) {}
            }
            final long sensorTsBefore = cs.lastSensorCheckMs;
            healthCheckHandler.postDelayed(() -> {
                if (cs.lastSensorCheckMs <= sensorTsBefore)
                    cs.setEsp(AppCore.ConnectionState.Status.DISCONNECTED);
                cs.setChecking(false);
            }, 5000);
        }

        private void verifyAndProcess(AppModel.SensorData data) {
            if (data.deviceId == null || data.deviceId.isEmpty()) return;
            data.userId = AppCore.AppHelper.getInstance(MonitoringService.this).getUserId();
            AppCore.DataManager.getInstance().publish(data);
            updateNotification(data);
        }

        private void updateNotification(AppModel.SensorData data) {
            String hr   = data.heartRate   > 0 ? String.valueOf((int) data.heartRate) : "--";
            String spo2 = data.spo2        > 0 ? String.valueOf((int) data.spo2)      : "--";
            String temp = data.temperature > 0
                    ? String.format(Locale.getDefault(), "%.1f", data.temperature) : "--";
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .notify(NOTIF_ID, buildNotification("Đang theo dõi sức khỏe", hr, spo2, temp));
        }

        private Notification buildNotification(String title, String hr, String spo2, String temp) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            return new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText("❤ " + hr + " bpm  |  💧 " + spo2 + "%  |  🌡 " + temp + "°C")
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setContentIntent(pi)
                    .build();
        }

        private void createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Theo dõi sức khỏe", NotificationManager.IMPORTANCE_LOW);
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                        .createNotificationChannel(ch);
            }
        }

        @Override
        public void onDestroy() {
            reconnectHandler.removeCallbacksAndMessages(null);
            healthCheckHandler.removeCallbacksAndMessages(null);
            AppNetwork.MqttHelper.getInstance().disconnect();
            AppCore.DataManager.getInstance().stopConsumer();
            super.onDestroy();
        }

        @Override public IBinder onBind(Intent i) { return null; }
    }

    // =========================================================
    //  MedicineAlarmReceiver
    // =========================================================
    public static class MedicineAlarmReceiver extends BroadcastReceiver {
        private static final String TAG              = "MedAlarm";
        private static final String CHANNEL_ID_ALARM = "medicine_alarm";

        /** Tổng số lần báo nếu user không tương tác (kể cả lần đầu). */
        public  static final int  TOTAL_REMINDERS    = 3;
        /** Khoảng cách giữa 2 lần báo (5 phút). */
        public  static final long SNOOZE_INTERVAL_MS = 5 * 60 * 1000L;
        /** Thời gian phát chuông + rung mỗi lần báo. */
        private static final long ALARM_SOUND_MS     = 15_000L;

        // MediaPlayer tĩnh để MedicineActionReceiver dừng ngay khi user bấm nút
        static MediaPlayer activePlayer   = null;
        static Vibrator    activeVibrator = null;

        // Intent extras
        public static final String EXTRA_SCHEDULE_ID   = "scheduleId";
        public static final String EXTRA_MEDICINE_NAME = "medicineName";
        public static final String EXTRA_NOTE          = "note";
        public static final String EXTRA_REPEAT_DAILY  = "repeatDaily";
        public static final String EXTRA_SCHEDULE_TIME = "scheduleTime";
        /** 0 = lần đầu (alarm gốc); 1..TOTAL_REMINDERS-1 = các lần báo lại */
        public static final String EXTRA_SNOOZE_INDEX  = "snoozeIndex";

        @Override
        public void onReceive(Context context, Intent intent) {
            final int     scheduleId   = intent.getIntExtra(EXTRA_SCHEDULE_ID, -1);
            final String  medicineName = intent.getStringExtra(EXTRA_MEDICINE_NAME);
            final String  note         = intent.getStringExtra(EXTRA_NOTE);
            final boolean repeatDaily  = intent.getBooleanExtra(EXTRA_REPEAT_DAILY, true);
            final String  scheduleTime = intent.getStringExtra(EXTRA_SCHEDULE_TIME);
            final int     snoozeIndex  = intent.getIntExtra(EXTRA_SNOOZE_INDEX, 0);

            if (scheduleId == -1 || medicineName == null) return;
            Log.d(TAG, "Alarm fired: id=" + scheduleId
                    + " reminder=" + (snoozeIndex + 1) + "/" + TOTAL_REMINDERS);

            // Đánh dấu đang chờ confirm
            AlarmHelper.setPendingConfirm(context, scheduleId, true);
            createAlarmChannel(context);

            // ── Phát chuông + rung ─────────────────────────────────
            playAlarmSound(context, ALARM_SOUND_MS);

            // ── Bắn notification có 2 action button ───────────────
            showAlarmNotification(context, scheduleId, medicineName, note,
                    repeatDaily, scheduleTime);

            // ★★★ FIX QUAN TRỌNG ★★★
            // Khi alarm GỐC fire, đặt LUÔN alarm cho ngày hôm sau.
            // Không chờ đến snooze cuối — vì snooze có thể bị Doze nuốt,
            // OS kill app, hoặc user dismiss notification.
            // Đặt requestCode = scheduleId với FLAG_UPDATE_CURRENT sẽ
            // REPLACE alarm cũ (đã consumed) → an toàn, không trùng.
            if (snoozeIndex == 0 && repeatDaily && scheduleTime != null) {
                AppModel.MedicineSchedule next = new AppModel.MedicineSchedule();
                next.id           = scheduleId;
                next.medicineName = medicineName;
                next.note         = note;
                next.scheduleTime = scheduleTime;
                next.repeatDaily  = true;
                AlarmHelper.setDailyAlarm(context, next);
                Log.d(TAG, "Daily alarm pre-scheduled for TOMORROW id=" + scheduleId);
            }

            // ── Snooze logic ───────────────────────────────────────
            // Lần cuối: KHÔNG snooze, sau khi chuông tắt → auto-skip nếu vẫn pending.
            if (snoozeIndex < TOTAL_REMINDERS - 1) {
                AppModel.MedicineSchedule s = new AppModel.MedicineSchedule();
                s.id            = scheduleId;
                s.medicineName  = medicineName;
                s.note          = note;
                s.repeatDaily   = repeatDaily;
                s.scheduleTime  = scheduleTime;
                AlarmHelper.setSnoozeAlarm(context, s, snoozeIndex + 1);
            } else {
                // Lần báo cuối — chờ chuông tắt rồi auto-skip
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (!AlarmHelper.isPendingConfirm(context, scheduleId)) return;
                    AlarmHelper.setPendingConfirm(context, scheduleId, false);
                    NotificationManager nm = (NotificationManager)
                            context.getSystemService(Context.NOTIFICATION_SERVICE);
                    nm.cancel(scheduleId);

                    new Thread(() -> {
                        try {
                            AppNetwork.RetrofitHelper.getInstance()
                                    .confirmMedicine(new AppModel.ConfirmRequest(scheduleId, "skipped"))
                                    .execute();
                            Log.d(TAG, "Auto-skipped after " + TOTAL_REMINDERS
                                    + " reminders: id=" + scheduleId);
                        } catch (Exception e) {
                            Log.e(TAG, "Auto-skip POST failed", e);
                        }
                    }).start();
                    // Không cần setDailyAlarm — đã pre-schedule ở lần đầu rồi.
                }, ALARM_SOUND_MS + 1_000L);
            }
        }

        private void playAlarmSound(Context context, long durationMs) {
            stopAlarmSound();
            try {
                Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (alarmUri == null) {
                    alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                }
                MediaPlayer mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                mp.setDataSource(context, alarmUri);
                mp.setLooping(true);
                mp.prepare();
                mp.start();
                activePlayer = mp;

                Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                activeVibrator = vibrator;
                if (vibrator != null) {
                    long[] pattern = {0, 800, 400, 800, 400};
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                    } else {
                        vibrator.vibrate(pattern, 0);
                    }
                }

                new Handler(Looper.getMainLooper()).postDelayed(MedicineAlarmReceiver::stopAlarmSound, durationMs);

            } catch (IOException | IllegalStateException e) {
                Log.e(TAG, "playAlarmSound error", e);
            }
        }

        static void stopAlarmSound() {
            try {
                if (activePlayer != null) {
                    if (activePlayer.isPlaying()) activePlayer.stop();
                    activePlayer.release();
                    activePlayer = null;
                }
            } catch (Exception ignored) {}
            try {
                if (activeVibrator != null) {
                    activeVibrator.cancel();
                    activeVibrator = null;
                }
            } catch (Exception ignored) {}
        }

        private void showAlarmNotification(Context context, int scheduleId,
                                           String medicineName, String note,
                                           boolean repeatDaily, String scheduleTime) {
            Intent takenIntent = new Intent(context, MedicineActionReceiver.class);
            takenIntent.putExtra(EXTRA_SCHEDULE_ID,   scheduleId);
            takenIntent.putExtra(EXTRA_MEDICINE_NAME, medicineName);
            takenIntent.putExtra(EXTRA_REPEAT_DAILY,  repeatDaily);
            takenIntent.putExtra(EXTRA_SCHEDULE_TIME, scheduleTime);
            takenIntent.putExtra("action", "taken");
            PendingIntent piTaken = PendingIntent.getBroadcast(
                    context, scheduleId * 10 + 1, takenIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Intent skippedIntent = new Intent(context, MedicineActionReceiver.class);
            skippedIntent.putExtra(EXTRA_SCHEDULE_ID,   scheduleId);
            skippedIntent.putExtra(EXTRA_MEDICINE_NAME, medicineName);
            skippedIntent.putExtra(EXTRA_REPEAT_DAILY,  repeatDaily);
            skippedIntent.putExtra(EXTRA_SCHEDULE_TIME, scheduleTime);
            skippedIntent.putExtra("action", "skipped");
            PendingIntent piSkipped = PendingIntent.getBroadcast(
                    context, scheduleId * 10 + 2, skippedIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            String contentText = medicineName;
            if (note != null && !note.isEmpty()) contentText += " — " + note;

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID_ALARM)
                    .setContentTitle("\uD83D\uDC8A Đến giờ uống thuốc!")
                    .setContentText(contentText)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(false)
                    .setOngoing(true)
                    .setVibrate(new long[]{0, 500, 200, 500})
                    .addAction(android.R.drawable.ic_menu_send, "Đã uống", piTaken)
                    .addAction(android.R.drawable.ic_delete,    "Bỏ qua",  piSkipped);

            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(scheduleId, builder.build());
        }

        private void createAlarmChannel(Context context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID_ALARM, "Nhắc uống thuốc", NotificationManager.IMPORTANCE_HIGH);
                ch.enableVibration(true);
                ch.setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .build());
                ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                        .createNotificationChannel(ch);
            }
        }
    }


    // =========================================================
    //  MedicineActionReceiver — MỚI
    //  Nhận intent khi guest bấm "Đã uống" hoặc "Bỏ qua"
    //  từ notification action button
    // =========================================================
    public static class MedicineActionReceiver extends BroadcastReceiver {
        private static final String TAG = "MedAction";

        @Override
        public void onReceive(Context context, Intent intent) {
            int    scheduleId   = intent.getIntExtra(MedicineAlarmReceiver.EXTRA_SCHEDULE_ID, -1);
            String action       = intent.getStringExtra("action");
            boolean repeatDaily = intent.getBooleanExtra(MedicineAlarmReceiver.EXTRA_REPEAT_DAILY, true);
            String scheduleTime = intent.getStringExtra(MedicineAlarmReceiver.EXTRA_SCHEDULE_TIME);
            String medicineName = intent.getStringExtra(MedicineAlarmReceiver.EXTRA_MEDICINE_NAME);

            if (scheduleId == -1 || action == null) return;
            Log.d(TAG, "Action received: id=" + scheduleId + " action=" + action);

            // 0. Dừng âm + rung NGAY LẬP TỨC khi user bấm bất kỳ nút nào
            MedicineAlarmReceiver.stopAlarmSound();

            // 1. Huỷ tất cả snooze alarm đang chờ
            AlarmHelper.cancelAllSnooze(context, scheduleId);

            // 2. Xoá trạng thái pending confirm
            AlarmHelper.setPendingConfirm(context, scheduleId, false);

            // 3. Dismiss notification
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(scheduleId);

            // 4. POST confirm lên server (background thread)
            new Thread(() -> {
                try {
                    AppNetwork.RetrofitHelper.getInstance()
                            .confirmMedicine(new AppModel.ConfirmRequest(scheduleId, action))
                            .execute();
                    Log.d(TAG, "Confirm posted: id=" + scheduleId + " action=" + action);
                } catch (Exception e) {
                    Log.e(TAG, "Confirm POST failed", e);
                }
            }).start();

            // 5. Đặt lại alarm ngày hôm sau nếu repeatDaily
            if (repeatDaily && scheduleTime != null && medicineName != null) {
                AppModel.MedicineSchedule s = new AppModel.MedicineSchedule();
                s.id           = scheduleId;
                s.medicineName = medicineName;
                s.scheduleTime = scheduleTime;
                s.repeatDaily  = true;
                AlarmHelper.setDailyAlarm(context, s);
                Log.d(TAG, "Daily alarm re-set for tomorrow: id=" + scheduleId);
            }
        }
    }

    // =========================================================
    //  AlarmHelper — dùng setAlarmClock, có ensureAllAlarmsScheduled
    // =========================================================
    public static class AlarmHelper {

        private static final String PREFS_PENDING = "medicine_pending";

        /** Đặt alarm gốc (lần đầu cho 1 lịch). */
        public static void setAlarm(Context context, AppModel.MedicineSchedule schedule) {
            Calendar cal = buildCalendar(schedule.scheduleTime);
            if (cal == null) return;
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                cal.add(Calendar.DATE, 1);
            }
            scheduleExact(context, cal.getTimeInMillis(),
                    buildAlarmIntent(context, schedule, 0));
            Log.d("AlarmHelper", "setAlarm id=" + schedule.id + " at=" + cal.getTime());
        }

        /** Đặt alarm cho ngày HÔM SAU (gọi sau khi confirm hoặc ngay từ alarm gốc). */
        public static void setDailyAlarm(Context context, AppModel.MedicineSchedule schedule) {
            Calendar cal = buildCalendar(schedule.scheduleTime);
            if (cal == null) return;
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                cal.add(Calendar.DATE, 1);
            }
            scheduleExact(context, cal.getTimeInMillis(),
                    buildAlarmIntent(context, schedule, 0));
            Log.d("AlarmHelper", "setDailyAlarm id=" + schedule.id + " at=" + cal.getTime());
        }

        /** Đặt snooze (báo lại sau SNOOZE_INTERVAL_MS). */
        public static void setSnoozeAlarm(Context context,
                                          AppModel.MedicineSchedule schedule,
                                          int snoozeIndex) {
            if (snoozeIndex >= MedicineAlarmReceiver.TOTAL_REMINDERS) {
                Log.d("AlarmHelper", "Max reminders reached id=" + schedule.id);
                return;
            }
            if (!isPendingConfirm(context, schedule.id)) {
                Log.d("AlarmHelper", "Not pending, skip snooze id=" + schedule.id);
                return;
            }
            long fireAt = System.currentTimeMillis()
                    + MedicineAlarmReceiver.SNOOZE_INTERVAL_MS;
            scheduleExact(context, fireAt,
                    buildAlarmIntent(context, schedule, snoozeIndex));
            Log.d("AlarmHelper", "setSnooze #" + snoozeIndex + " id=" + schedule.id);
        }

        public static void cancelAlarm(Context context, int scheduleId) {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent intent = buildBaseIntent(context);
            intent.putExtra(MedicineAlarmReceiver.EXTRA_SCHEDULE_ID, scheduleId);
            PendingIntent pi = PendingIntent.getBroadcast(
                    context, scheduleId, intent,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pi != null && am != null) { am.cancel(pi); pi.cancel(); }
        }

        public static void cancelAllSnooze(Context context, int scheduleId) {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            for (int i = 1; i < MedicineAlarmReceiver.TOTAL_REMINDERS; i++) {
                int rc = scheduleId * 10000 + i;
                Intent intent = buildBaseIntent(context);
                intent.putExtra(MedicineAlarmReceiver.EXTRA_SCHEDULE_ID, scheduleId);
                intent.putExtra(MedicineAlarmReceiver.EXTRA_SNOOZE_INDEX, i);
                PendingIntent pi = PendingIntent.getBroadcast(
                        context, rc, intent,
                        PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
                if (pi != null) { am.cancel(pi); pi.cancel(); }
            }
            Log.d("AlarmHelper", "cancelAllSnooze id=" + scheduleId);
        }

        private static final String CHANNEL_ID_SCHEDULE_CHANGED = "medicine_schedule_changed";

        /**
         * FIX: Bắn thông báo kiểu "lịch thuốc của bạn đã thay đổi" khi phát hiện
         * background refresh (health check / service start) lấy được dữ liệu
         * khác với local cache đang có. Chỉ dùng cho refresh chạy NGẦM —
         * không bắn khi user tự mở màn hình lịch thuốc (đã tự thấy trên UI rồi).
         */
        public static void showScheduleChangedNotification(Context context, String summary) {
            createScheduleChangedChannel(context);
            NotificationCompat.Builder builder =
                    new NotificationCompat.Builder(context, CHANNEL_ID_SCHEDULE_CHANGED)
                            .setContentTitle("💊 Lịch uống thuốc của bạn đã thay đổi")
                            .setContentText(summary)
                            .setStyle(new NotificationCompat.BigTextStyle().bigText(summary))
                            .setSmallIcon(android.R.drawable.ic_popup_reminder)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .setAutoCancel(true);
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(90000, builder.build());
        }

        private static void createScheduleChangedChannel(Context context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID_SCHEDULE_CHANGED, "Thay đổi lịch thuốc",
                        NotificationManager.IMPORTANCE_DEFAULT);
                ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                        .createNotificationChannel(ch);
            }
        }

        public static void setPendingConfirm(Context context, int scheduleId, boolean pending) {
            context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("pending_" + scheduleId, pending)
                    .apply();
        }

        public static boolean isPendingConfirm(Context context, int scheduleId) {
            return context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
                    .getBoolean("pending_" + scheduleId, false);
        }

        public static void setAllAlarms(Context context, List<AppModel.MedicineSchedule> list) {
            for (AppModel.MedicineSchedule s : list) setAlarm(context, s);
        }

        public static void cancelAllAlarms(Context context, List<Integer> ids) {
            for (int id : ids) {
                cancelAlarm(context, id);
                cancelAllSnooze(context, id);
            }
        }

        /**
         * MỚI: Đảm bảo tất cả alarm đều có trong AlarmManager.
         * Đọc list lịch thuốc từ Room DB local rồi setAlarm cho từng cái.
         * Gọi từ MonitoringService.onStartCommand để chống mất alarm sau:
         *   - Force-stop app
         *   - OS giết app, dọn dẹp
         *   - App update
         *   - Đổi giờ hệ thống
         *
         * Vì dùng FLAG_UPDATE_CURRENT trong buildAlarmIntent, nếu alarm
         * đã có sẵn thì sẽ được replace (không trùng).
         */
        public static void ensureAllAlarmsScheduled(Context context) {
            new Thread(() -> {
                try {
                    int userId = AppCore.AppHelper.getInstance(context).getUserIdInt();
                    if (userId <= 0) return;
                    List<AppDatabase.MedicineEntity> list =
                            AppDatabase.AppRoomDatabase.getInstance(context)
                                    .appDao().getMedicineByUserSync(userId);
                    int count = 0;
                    for (AppDatabase.MedicineEntity e : list) {
                        AppModel.MedicineSchedule s = new AppModel.MedicineSchedule();
                        s.id           = e.id;
                        s.userId       = e.userId;
                        s.medicineName = e.medicineName;
                        s.scheduleTime = e.scheduleTime;
                        s.note         = e.note;
                        s.repeatDaily  = e.repeatDaily; // ← FIX: lấy giá trị thật từ DB local
                        setAlarm(context, s);
                        count++;
                    }
                    Log.d("AlarmHelper", "ensureAllAlarmsScheduled: " + count + " alarms ready");
                } catch (Exception e) {
                    Log.e("AlarmHelper", "ensureAllAlarmsScheduled error", e);
                }
            }).start();
        }

        // ── Private helpers ────────────────────────────────────

        private static Intent buildBaseIntent(Context context) {
            return new Intent(context, MedicineAlarmReceiver.class);
        }

        private static PendingIntent buildAlarmIntent(Context context,
                                                      AppModel.MedicineSchedule schedule,
                                                      int snoozeIndex) {
            Intent intent = buildBaseIntent(context);
            intent.putExtra(MedicineAlarmReceiver.EXTRA_SCHEDULE_ID,   schedule.id);
            intent.putExtra(MedicineAlarmReceiver.EXTRA_MEDICINE_NAME, schedule.medicineName);
            intent.putExtra(MedicineAlarmReceiver.EXTRA_NOTE,          schedule.note);
            intent.putExtra(MedicineAlarmReceiver.EXTRA_REPEAT_DAILY,  schedule.repeatDaily);
            intent.putExtra(MedicineAlarmReceiver.EXTRA_SCHEDULE_TIME, schedule.scheduleTime);
            intent.putExtra(MedicineAlarmReceiver.EXTRA_SNOOZE_INDEX,  snoozeIndex);

            int requestCode = (snoozeIndex == 0)
                    ? schedule.id
                    : schedule.id * 10000 + snoozeIndex;

            return PendingIntent.getBroadcast(
                    context, requestCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        /**
         * Dùng setAlarmClock — API duy nhất Android cam kết:
         *   - KHÔNG bị Doze trì hoãn
         *   - KHÔNG bị throttle 9-15 phút như setExactAndAllowWhileIdle
         *   - Fire đúng giây kể cả khi máy đang sleep
         *
         * Đổi lại: status bar hiện icon đồng hồ nhỏ khi sắp đến lịch
         * (giống Clock app) — đây là hành vi mong muốn.
         *
         * Android 12+ (API 31) cần SCHEDULE_EXACT_ALARM hoặc USE_EXACT_ALARM
         * (manifest đã có cả 2). Nếu user tắt quyền thủ công → fallback inexact.
         */
        private static void scheduleExact(Context context, long triggerAtMs, PendingIntent pi) {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && !am.canScheduleExactAlarms()) {
                Log.w("AlarmHelper", "No exact alarm permission, fallback to inexact");
                am.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
                return;
            }

            Intent showIntent = new Intent(context, MainActivity.class);
            showIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent showPi = PendingIntent.getActivity(
                    context, 0, showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            AlarmManager.AlarmClockInfo info =
                    new AlarmManager.AlarmClockInfo(triggerAtMs, showPi);
            am.setAlarmClock(info, pi);
        }

        private static Calendar buildCalendar(String scheduleTime) {
            if (scheduleTime == null) return null;
            try {
                String[] parts = scheduleTime.split(":");
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0]));
                cal.set(Calendar.MINUTE,      Integer.parseInt(parts[1]));
                cal.set(Calendar.SECOND,      0);
                cal.set(Calendar.MILLISECOND, 0);
                return cal;
            } catch (Exception e) {
                Log.e("AlarmHelper", "buildCalendar error: " + scheduleTime, e);
                return null;
            }
        }
    }

    // =========================================================
    //  SyncWorker — giữ nguyên
    // =========================================================
    public static class SyncWorker extends Worker {
        private static final String TAG = "SyncWorker";
        public SyncWorker(Context context, WorkerParameters params) { super(context, params); }

        @Override
        public Result doWork() {
            try {
                List<AppDatabase.SensorLogEntity> unsynced =
                        AppDatabase.AppRoomDatabase.getInstance(getApplicationContext())
                                .appDao().getUnsyncedLogs();
                for (AppDatabase.SensorLogEntity entity : unsynced) {
                    AppModel.SensorData data = new AppModel.SensorData();
                    data.heartRate   = entity.heartRate;
                    data.spo2        = entity.spo2;
                    data.temperature = entity.temperature;
                    data.deviceId    = entity.deviceId;
                    data.source      = "BLE_SYNC";
                    data.userId      = String.valueOf(entity.userId);
                    data.timestamp   = entity.timestamp;
                    try {
                        retrofit2.Response<okhttp3.ResponseBody> resp =
                                AppNetwork.RetrofitHelper.getInstance().logSensor(data).execute();
                        if (resp.isSuccessful()) {
                            AppDatabase.AppRoomDatabase.getInstance(getApplicationContext())
                                    .appDao().markAsSynced(entity.id);
                        }
                    } catch (IOException e) {
                        Log.w(TAG, "Sync fail id=" + entity.id);
                    }
                }
                AppCore.AppHelper.getInstance(getApplicationContext())
                        .saveLastSyncTime(System.currentTimeMillis());
                return Result.success();
            } catch (Exception e) {
                return Result.retry();
            }
        }
    }
}