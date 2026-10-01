package com.example.healthmonitor;

/**
 * AppModel.java
 * Thêm vào MedicineSchedule: repeatDaily, lastAction, lastActionAt
 */
public class AppModel {

    // ========== SensorData ========== (giữ nguyên)
    public static class SensorData {
        public float heartRate;
        public float spo2;
        public float temperature;
        public String deviceId;
        public String userId;
        public String source;
        public long timestamp;

        public SensorData() {}

        public SensorData(float heartRate, float spo2, float temperature,
                          String deviceId, String userId, String source) {
            this.heartRate   = heartRate;
            this.spo2        = spo2;
            this.temperature = temperature;
            this.deviceId    = deviceId != null ? deviceId : "";
            this.userId      = userId   != null ? userId   : "0";
            this.source      = source   != null ? source   : "";
            this.timestamp   = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return "SensorData{hr=" + heartRate + ", spo2=" + spo2
                    + ", temp=" + temperature + ", dev=" + deviceId
                    + ", src=" + source + "}";
        }
    }

    // ========== User ========== (giữ nguyên)
    public static class User {
        public int    id;
        public int    createdBy;
        public String username;
        public String passwordHash;
        public String role;
        public String status;
    }

    // ========== Device ========== (giữ nguyên)
    public static class Device {
        public int     id;
        public int     registeredBy;
        public String  deviceId;
        public boolean active;
    }

    // ========== MedicineSchedule ========== ← CẬP NHẬT
    public static class MedicineSchedule {
        public int     id;
        public int     userId;
        public String  medicineName;
        public String  scheduleTime;   // "HH:mm"
        public String  note;

        // ── MỚI ──────────────────────────────────────────────
        /** true = nhắc hàng ngày; false = chỉ nhắc 1 lần rồi thôi */
        public boolean repeatDaily = true;

        /** "taken" | "skipped" | null — hành động GẦN NHẤT của guest */
        public String  lastAction;

        /** ISO string "yyyy-MM-dd HH:mm:ss" hoặc null */
        public String  lastActionAt;
    }

    // ========== ConfirmRequest ========== ← MỚI
    /** Body gửi lên medicine_confirm.php */
    public static class ConfirmRequest {
        public int    id;
        public String action;  // "taken" | "skipped"

        public ConfirmRequest(int id, String action) {
            this.id     = id;
            this.action = action;
        }
    }

    // ========== LoginRequest ========== (giữ nguyên)
    public static class LoginRequest {
        public String username;
        public String password;

        public LoginRequest(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    // ========== LoginResponse ========== (giữ nguyên)
    public static class LoginResponse {
        public String token;
        public String role;
        public int    userId;
    }
}