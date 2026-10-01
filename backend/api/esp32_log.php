<?php
// ============================================================
// API: POST /healthcare/api/esp32_log.php
// Endpoint cho ESP32 gửi data trực tiếp qua HTTP (không cần JWT)
// Server verify dựa trên deviceId có đăng ký trong bảng devices
//
// MODEL B (multi-user): ESP32 gửi kèm "userId" — userId của guest
// đang nhấn ĐO NGAY. Nếu không gửi (uid=0 hoặc thiếu) → fallback
// devices.registered_by để giữ tương thích ngược.
//
// Luôn ghi vào sensor_log_http với source = HTTP_ESP32
// Đây là nguồn DUY NHẤT phục vụ biểu đồ (sensor_history.php query).
// ============================================================
require_once __DIR__ . '/../config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    errorResponse("Method not allowed", 405);
}

$body = getJsonBody();

$deviceId    = isset($body['devId'])   ? trim($body['devId'])  : '';
$heartRate   = isset($body['hr'])      ? (float)$body['hr']    : 0.0;
$spo2        = isset($body['spo2'])    ? (float)$body['spo2']  : 0.0;
$temperature = isset($body['temp'])    ? (float)$body['temp']  : 0.0;
$bodyUserId  = isset($body['userId'])  ? (int)$body['userId']  : 0;

if (empty($deviceId)) {
    errorResponse("Device ID is required", 400);
}

// ── Verify deviceId có trong bảng devices và active ─────────
$check = $conn->prepare("SELECT registered_by FROM devices WHERE device_id = ? AND active = TRUE");
$check->bind_param("s", $deviceId);
$check->execute();
$result = $check->get_result();

if ($result->num_rows === 0) {
    $check->close();
    errorResponse("Device not registered: " . $deviceId, 403);
}

$row = $result->fetch_assoc();
$registeredBy = (int)$row['registered_by'];
$check->close();

// ── Quyết định user_id cho row sắp insert ────────────────────
// Ưu tiên userId do ESP32 gửi (= guest đang nhấn ĐO NGAY).
// Fallback registered_by (admin) nếu ESP32 không gửi (firmware cũ
// hoặc lệnh START_MEASURE chuỗi thuần không có uid).
$userId = $bodyUserId > 0 ? $bodyUserId : $registeredBy;

// ── (An toàn) verify userId tồn tại trong users ──────────────
// Phòng trường hợp app gửi userId rác → tránh insert mồ côi.
if ($bodyUserId > 0) {
    $vstmt = $conn->prepare("SELECT id FROM users WHERE id = ?");
    $vstmt->bind_param("i", $userId);
    $vstmt->execute();
    if ($vstmt->get_result()->num_rows === 0) {
        $vstmt->close();
        // userId không tồn tại → fallback về registered_by
        $userId = $registeredBy;
    } else {
        $vstmt->close();
    }
}

// ── Lưu vào sensor_log_http ──────────────────────────────
$source = 'HTTP_ESP32';
$stmt = $conn->prepare(
    "INSERT INTO sensor_log_http (user_id, device_id, heart_rate, spo2, temperature, source)
     VALUES (?, ?, ?, ?, ?, ?)"
);
$stmt->bind_param("isddds", $userId, $deviceId, $heartRate, $spo2, $temperature, $source);

if ($stmt->execute()) {
    $newId = $stmt->insert_id;
    $stmt->close();
    jsonResponse([
        "message" => "OK",
        "id"      => $newId,
        "userId"  => $userId
    ], 201);
} else {
    $stmt->close();
    errorResponse("Failed to log", 500);
}
?>