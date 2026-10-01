package com.example.healthmonitor;

import android.content.Context;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * MedicineLocalSync — FILE MỚI
 *
 * Helper đồng bộ danh sách thuốc server → local Room DB.
 * Mục đích: để BootReceiver và AlarmHelper.ensureAllAlarmsScheduled()
 * có dữ liệu khôi phục alarm khi reboot/force-stop, KỂ CẢ KHI MẤT MẠNG.
 *
 * Gọi từ:
 *   - UserTrackingFragment.MyMedicineFragment.loadMedicines (sau khi network OK)
 *   - AdminMedicineFragment.loadMedicines (sau khi network OK)
 *   - AdminMedicineFragment khi save / delete 1 lịch (upsertLocal / deleteLocal)
 */
public class MedicineLocalSync {
    private static final String TAG = "MedicineLocalSync";

    /** Đồng bộ TOÀN BỘ list của 1 user → local (atomic clear + insert). */
    public static void syncToLocal(Context context,
                                   int userId,
                                   List<AppModel.MedicineSchedule> serverList) {
        if (context == null || userId <= 0 || serverList == null) return;
        Context appCtx = context.getApplicationContext();

        new Thread(() -> {
            try {
                List<AppDatabase.MedicineEntity> entities = new ArrayList<>();
                for (AppModel.MedicineSchedule s : serverList) {
                    AppDatabase.MedicineEntity e = new AppDatabase.MedicineEntity();
                    e.id            = s.id;
                    e.userId        = s.userId > 0 ? s.userId : userId;
                    e.medicineName  = s.medicineName;
                    e.scheduleTime  = s.scheduleTime;
                    e.note          = s.note;
                    e.repeatDaily   = s.repeatDaily;
                    entities.add(e);
                }
                AppDatabase.AppRoomDatabase.getInstance(appCtx)
                        .appDao()
                        .replaceMedicineForUser(userId, entities);
                Log.d(TAG, "Synced " + entities.size() + " medicine(s) → local (user=" + userId + ")");

                // Sau khi sync, gọi luôn ensureAllAlarmsScheduled để cover trường hợp
                // user mới cài app và chưa từng có alarm nào trong AlarmManager.
                AppService.AlarmHelper.ensureAllAlarmsScheduled(appCtx);
            } catch (Exception ex) {
                Log.e(TAG, "syncToLocal error", ex);
            }
        }).start();
    }

    /** Upsert 1 lịch — dùng khi admin save 1 lịch thành công. */
    public static void upsertLocal(Context context, AppModel.MedicineSchedule s) {
        if (context == null || s == null || s.id <= 0) return;
        Context appCtx = context.getApplicationContext();
        new Thread(() -> {
            try {
                AppDatabase.MedicineEntity e = new AppDatabase.MedicineEntity();
                e.id            = s.id;
                e.userId        = s.userId;
                e.medicineName  = s.medicineName;
                e.scheduleTime  = s.scheduleTime;
                e.note          = s.note;
                e.repeatDaily   = s.repeatDaily;
                AppDatabase.AppRoomDatabase.getInstance(appCtx)
                        .appDao()
                        .upsertMedicine(e);
                Log.d(TAG, "Upserted local id=" + s.id);
            } catch (Exception ex) {
                Log.e(TAG, "upsertLocal error", ex);
            }
        }).start();
    }

    /** Xoá 1 lịch khỏi local — dùng khi admin delete trên server. */
    public static void deleteLocal(Context context, int scheduleId) {
        if (context == null || scheduleId <= 0) return;
        Context appCtx = context.getApplicationContext();
        new Thread(() -> {
            try {
                AppDatabase.AppRoomDatabase.getInstance(appCtx)
                        .appDao()
                        .deleteMedicineById(scheduleId);
                Log.d(TAG, "Deleted local id=" + scheduleId);
            } catch (Exception ex) {
                Log.e(TAG, "deleteLocal error", ex);
            }
        }).start();
    }

    /**
     * FIX: Luôn cố gắng lấy lịch thuốc MỚI NHẤT từ server trước, ghi đè local.
     * Nếu không có mạng / server lỗi → fallback dùng cache local hiện có.
     * Gọi mỗi khi MonitoringService start VÀ định kỳ (health check) để đảm bảo
     * local cache không bị "đứng hình" ở lần sync thành công đầu tiên khi
     * người dùng không chủ động mở màn hình danh sách thuốc lúc có mạng.
     */
    public static void refreshFromServerThenSchedule(Context context) {
        if (context == null) return;
        Context appCtx = context.getApplicationContext();
        int userId = AppCore.AppHelper.getInstance(appCtx).getUserIdInt();
        if (userId <= 0) {
            // Chưa đăng nhập / không xác định được user -> chỉ có thể dùng cache local (nếu có)
            AppService.AlarmHelper.ensureAllAlarmsScheduled(appCtx);
            return;
        }
        AppNetwork.RetrofitHelper.getInstance().getMedicineList(userId)
                .enqueue(new retrofit2.Callback<List<AppModel.MedicineSchedule>>() {
                    @Override
                    public void onResponse(retrofit2.Call<List<AppModel.MedicineSchedule>> call,
                                           retrofit2.Response<List<AppModel.MedicineSchedule>> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            List<AppModel.MedicineSchedule> newList = response.body();
                            Log.d(TAG, "refreshFromServerThenSchedule: server OK ("
                                    + newList.size() + " lịch) -> so sánh & ghi đè local");

                            // So sánh với cache cũ TRƯỚC khi ghi đè -> nếu khác thì báo cho user biết
                            new Thread(() -> {
                                try {
                                    List<AppModel.MedicineSchedule> oldList = loadLocalSync(appCtx, userId);
                                    String diff = buildDiffSummary(oldList, newList);
                                    if (diff != null) {
                                        Log.d(TAG, "Phát hiện thay đổi lịch thuốc: " + diff);
                                        AppService.AlarmHelper.showScheduleChangedNotification(appCtx, diff);
                                    }
                                } catch (Exception ex) {
                                    Log.e(TAG, "buildDiffSummary error", ex);
                                }
                                // syncToLocal tự gọi ensureAllAlarmsScheduled() sau khi replace xong
                                syncToLocal(appCtx, userId, newList);
                            }).start();
                        } else {
                            Log.w(TAG, "refreshFromServerThenSchedule: server trả lỗi ("
                                    + response.code() + ") -> fallback cache local");
                            AppService.AlarmHelper.ensureAllAlarmsScheduled(appCtx);
                        }
                    }

                    @Override
                    public void onFailure(retrofit2.Call<List<AppModel.MedicineSchedule>> call, Throwable t) {
                        Log.w(TAG, "refreshFromServerThenSchedule: offline/thất bại ("
                                + t.getMessage() + ") -> dùng cache local mới nhất đang có");
                        AppService.AlarmHelper.ensureAllAlarmsScheduled(appCtx);
                    }
                });
    }

    /**
     * So sánh danh sách lịch thuốc cũ (local) và mới (server) theo id.
     * Trả về chuỗi mô tả thay đổi (tiếng Việt), hoặc null nếu không có gì khác.
     */
    private static String buildDiffSummary(List<AppModel.MedicineSchedule> oldList,
                                           List<AppModel.MedicineSchedule> newList) {
        java.util.Map<Integer, AppModel.MedicineSchedule> oldMap = new java.util.HashMap<>();
        for (AppModel.MedicineSchedule s : oldList) oldMap.put(s.id, s);

        java.util.Map<Integer, AppModel.MedicineSchedule> newMap = new java.util.HashMap<>();
        for (AppModel.MedicineSchedule s : newList) newMap.put(s.id, s);

        List<String> changes = new ArrayList<>();

        for (AppModel.MedicineSchedule n : newList) {
            AppModel.MedicineSchedule o = oldMap.get(n.id);
            if (o == null) {
                changes.add("Thêm mới: " + n.medicineName + " lúc " + n.scheduleTime);
            } else if (!safeEquals(o.scheduleTime, n.scheduleTime)
                    || !safeEquals(o.medicineName, n.medicineName)
                    || o.repeatDaily != n.repeatDaily) {
                changes.add(n.medicineName + ": " + o.scheduleTime + " → " + n.scheduleTime);
            }
        }
        for (AppModel.MedicineSchedule o : oldList) {
            if (!newMap.containsKey(o.id)) {
                changes.add("Đã xoá: " + o.medicineName + " (" + o.scheduleTime + ")");
            }
        }

        if (changes.isEmpty()) return null;
        return String.join("; ", changes);
    }

    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Load list từ local DB (dùng làm offline fallback khi network fail). */
    public static List<AppModel.MedicineSchedule> loadLocalSync(Context context, int userId) {
        List<AppModel.MedicineSchedule> out = new ArrayList<>();
        if (context == null || userId <= 0) return out;
        try {
            List<AppDatabase.MedicineEntity> rows =
                    AppDatabase.AppRoomDatabase.getInstance(context.getApplicationContext())
                            .appDao().getMedicineByUserSync(userId);
            for (AppDatabase.MedicineEntity e : rows) {
                AppModel.MedicineSchedule s = new AppModel.MedicineSchedule();
                s.id           = e.id;
                s.userId       = e.userId;
                s.medicineName = e.medicineName;
                s.scheduleTime = e.scheduleTime;
                s.note         = e.note;
                s.repeatDaily  = e.repeatDaily;
                out.add(s);
            }
        } catch (Exception ex) {
            Log.e(TAG, "loadLocalSync error", ex);
        }
        return out;
    }
}