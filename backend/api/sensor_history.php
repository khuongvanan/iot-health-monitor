<?php
// ============================================================
// API: GET /healthcare/api/sensor_history.php?range=today&userId=3
// Header: Authorization: Bearer <token>
// range: "today" | "week" | "month"
//
// MODEL B (multi-user):
//   - Guest: chỉ xem data của chính mình (lấy userId từ JWT,
//            bỏ qua mọi userId trong query string).
//   - Admin: phải truyền ?userId=X để chọn user muốn xem.
//
// CHỈ query sensor_log_http (source = HTTP_ESP32)
// → Mỗi lần đo chỉ có 1 row → biểu đồ không bị trùng điểm.
// (sensor_log_mqtt và sensor_log_ble chỉ để lưu trữ/audit,
//  không phục vụ biểu đồ.)
// ============================================================
require_once __DIR__ . '/../config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    errorResponse("Method not allowed", 405);
}

$auth = requireAuth();

$range = isset($_GET['range']) ? $_GET['range'] : 'today';

switch ($range) {
    case 'week':  $interval = "INTERVAL 7 DAY";  break;
    case 'month': $interval = "INTERVAL 30 DAY"; break;
    default:      $interval = "INTERVAL 1 DAY";  break;
}

// ── Xác định userId được phép xem ──────────────────────────
$queryUserId = isset($_GET['userId']) ? (int)$_GET['userId'] : 0;

if ($auth['role'] === 'admin') {
    // Admin: phải chọn user cụ thể từ dropdown trên app
    if ($queryUserId <= 0) {
        errorResponse("userId is required for admin queries", 400);
    }
    $userId = $queryUserId;
} else {
    // Guest: luôn xem của chính mình, bất kể query string truyền gì
    $userId = (int)$auth['userId'];
}

// ── Lấy data theo user_id ───────────────────────────────────
$sql = "SELECT
            heart_rate  AS heartRate,
            spo2,
            temperature,
            device_id   AS deviceId,
            user_id     AS userId,
            source,
            UNIX_TIMESTAMP(timestamp) * 1000 AS timestamp
        FROM sensor_log_http
        WHERE user_id = ?
          AND device_id IN (
                SELECT device_id FROM devices WHERE active = TRUE
            )
          AND timestamp >= NOW() - $interval
          AND heart_rate > 0
        ORDER BY timestamp ASC
        LIMIT 500";

$stmt = $conn->prepare($sql);
$stmt->bind_param("i", $userId);
$stmt->execute();
$result = $stmt->get_result();

$history = [];
while ($row = $result->fetch_assoc()) {
    $row['heartRate']   = (float)$row['heartRate'];
    $row['spo2']        = (float)$row['spo2'];
    $row['temperature'] = (float)$row['temperature'];
    $row['userId']      = (string)$row['userId'];
    $row['timestamp']   = (int)$row['timestamp'];
    $history[] = $row;
}
$stmt->close();

jsonResponse($history);
?>