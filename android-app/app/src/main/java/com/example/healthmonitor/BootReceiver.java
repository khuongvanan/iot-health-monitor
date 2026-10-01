package com.example.healthmonitor;

import android.content.*;
import android.util.Log;
import java.util.List;

/**
 * BootReceiver.java  (PATCHED)
 *
 * THAY ĐỔI so với bản ZIP gốc:
 *   - Đọc thêm trường repeatDaily từ MedicineEntity
 *   - Dùng getAllMedicineSync (an toàn hơn nếu sau này multi-user)
 *   - Thêm log cảnh báo khi DB rỗng
 *
 * Khi nào file này có hiệu lực:
 *   - Người dùng reboot điện thoại (kể cả khi MẤT MẠNG)
 *   - Đọc lịch thuốc đã được MedicineLocalSync sync xuống trước đó
 *   - Đặt lại toàn bộ alarm
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }
        Log.d(TAG, "Boot detected (" + action + "), restoring alarms...");

        AppCore.AppHelper helper = AppCore.AppHelper.getInstance(context);
        if (!helper.isSessionValid()) {
            Log.d(TAG, "No valid session, skip restore");
            return;
        }

        new Thread(() -> {
            try {
                List<AppDatabase.MedicineEntity> list =
                        AppDatabase.AppRoomDatabase.getInstance(context)
                                .appDao().getAllMedicineSync();

                int count = 0;
                for (AppDatabase.MedicineEntity e : list) {
                    AppModel.MedicineSchedule s = new AppModel.MedicineSchedule();
                    s.id           = e.id;
                    s.userId       = e.userId;
                    s.medicineName = e.medicineName;
                    s.scheduleTime = e.scheduleTime;
                    s.note         = e.note;
                    s.repeatDaily  = e.repeatDaily;
                    AppService.AlarmHelper.setAlarm(context, s);
                    count++;
                }
                Log.d(TAG, "Restored " + count + " alarm(s) from local DB");

                if (count == 0) {
                    Log.w(TAG, "Local DB rỗng — user chưa từng mở Tracking/Admin "
                            + "khi online để sync. Khi có mạng mở app sẽ tự sync và đặt lại.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error restoring alarms", e);
            }
        }).start();
    }
}