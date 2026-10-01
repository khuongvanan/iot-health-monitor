package com.example.healthmonitor;

import android.content.Context;
import androidx.lifecycle.LiveData;
import androidx.room.*;
import java.util.List;

/**
 * AppDatabase.java  (PATCHED v2)
 *
 * THAY ĐỔI so với bản ZIP gốc:
 *   - MedicineEntity:
 *       + Bỏ autoGenerate cho id  → giữ đúng id từ server (alarm requestCode dùng id này)
 *       + Thêm trường repeatDaily
 *   - AppDao đổi từ interface → abstract class để có @Transaction
 *   - Thêm DAO method:
 *       + upsertMedicine / upsertAllMedicine (REPLACE)
 *       + deleteMedicineByUser
 *       + getAllMedicineSync
 *       + replaceMedicineForUser  (atomic clear + insert lại — gọi khi sync từ server)
 *   - VERSION database tăng 1 → 2 (Room sẽ wipe DB cũ, không vấn đề vì DB cũ rỗng)
 */
public class AppDatabase {

    // ===================================================
    // ENTITY 1: SensorLogEntity — log cảm biến từ BLE
    // ===================================================
    @Entity(tableName = "sensor_log_ble")
    public static class SensorLogEntity {
        @PrimaryKey(autoGenerate = true)
        public int     id;
        public int     userId;
        public String  deviceId;
        public float   heartRate;
        public float   spo2;
        public float   temperature;
        public String  source;
        public long    timestamp;
        public boolean synced = false;
    }

    // ===================================================
    // ENTITY 2: MedicineEntity — lịch uống thuốc local
    // ===================================================
    @Entity(tableName = "medicine_local")
    public static class MedicineEntity {
        @PrimaryKey
        public int     id;                  // id từ server, KHÔNG autoGenerate
        public int     userId;
        public String  medicineName;
        public String  scheduleTime;
        public String  note;
        public boolean repeatDaily = true;  // ← MỚI
    }

    // ===================================================
    // DAO — abstract class để có @Transaction
    // ===================================================
    @Dao
    public abstract static class AppDao {

        // --- Sensor log ---
        @Insert
        public abstract void insertLog(SensorLogEntity log);

        @Query("SELECT * FROM sensor_log_ble WHERE userId = :userId ORDER BY timestamp DESC")
        public abstract LiveData<List<SensorLogEntity>> getLogsByUser(int userId);

        @Query("SELECT * FROM sensor_log_ble WHERE userId = :userId " +
                "AND timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
        public abstract List<SensorLogEntity> getLogsByDateRange(int userId, long start, long end);

        @Query("SELECT * FROM sensor_log_ble WHERE synced = 0 LIMIT 50")
        public abstract List<SensorLogEntity> getUnsyncedLogs();

        @Query("UPDATE sensor_log_ble SET synced = 1 WHERE id = :id")
        public abstract void markAsSynced(int id);

        @Query("DELETE FROM sensor_log_ble WHERE timestamp < :cutoff")
        public abstract void deleteOldLogs(long cutoff);

        // --- Medicine ---
        @Insert(onConflict = OnConflictStrategy.REPLACE)
        public abstract void upsertMedicine(MedicineEntity medicine);

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        public abstract void upsertAllMedicine(List<MedicineEntity> list);

        @Query("SELECT * FROM medicine_local WHERE userId = :userId ORDER BY scheduleTime ASC")
        public abstract LiveData<List<MedicineEntity>> getMedicineByUser(int userId);

        @Query("SELECT * FROM medicine_local WHERE userId = :userId ORDER BY scheduleTime ASC")
        public abstract List<MedicineEntity> getMedicineByUserSync(int userId);

        @Query("SELECT * FROM medicine_local ORDER BY scheduleTime ASC")
        public abstract List<MedicineEntity> getAllMedicineSync();

        @Query("DELETE FROM medicine_local WHERE id = :id")
        public abstract void deleteMedicineById(int id);

        @Query("DELETE FROM medicine_local WHERE userId = :userId")
        public abstract void deleteMedicineByUser(int userId);

        /** Atomic: xoá all của user rồi insert lại — gọi sau khi load từ server */
        @Transaction
        public void replaceMedicineForUser(int userId, List<MedicineEntity> list) {
            deleteMedicineByUser(userId);
            if (list != null && !list.isEmpty()) {
                upsertAllMedicine(list);
            }
        }
    }

    // ===================================================
    // DATABASE — Singleton
    // ===================================================
    @androidx.room.Database(
            entities = {SensorLogEntity.class, MedicineEntity.class},
            version  = 2,                       // ← TĂNG vì đổi schema
            exportSchema = false
    )
    public abstract static class AppRoomDatabase extends RoomDatabase {
        public abstract AppDao appDao();

        private static volatile AppRoomDatabase INSTANCE;

        public static AppRoomDatabase getInstance(Context context) {
            if (INSTANCE == null) {
                synchronized (AppRoomDatabase.class) {
                    if (INSTANCE == null) {
                        INSTANCE = Room.databaseBuilder(
                                        context.getApplicationContext(),
                                        AppRoomDatabase.class,
                                        "healthcare_db"
                                ).fallbackToDestructiveMigration()
                                .build();
                    }
                }
            }
            return INSTANCE;
        }
    }
}