<?php
// ============================================================
// API: GET /healthcare/api/devices_my.php
// Header: Authorization: Bearer <token>
//
// Trả về danh sách tất cả thiết bị đang active trên server.
// Bất kỳ user đăng nhập nào cũng có thể gọi (cả admin lẫn guest).
// Dùng để khôi phục deviceId sau khi logout/login hoặc cài lại app.
//
// Response: [{"id":1,"deviceId":"ESP32_H1","registeredBy":1,"active":true}, ...]
// ============================================================
require_once __DIR__ . '/../config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    errorResponse("Method not allowed", 405);
}

requireAuth();   // Chỉ cần token hợp lệ, không phân biệt role

$sql = "SELECT id, device_id AS deviceId, registered_by AS registeredBy, active
        FROM devices
        WHERE active = TRUE
        ORDER BY id ASC";
$result = $conn->query($sql);

$devices = [];
while ($row = $result->fetch_assoc()) {
    $row['id']           = (int)$row['id'];
    $row['registeredBy'] = (int)$row['registeredBy'];
    $row['active']       = (bool)$row['active'];
    $devices[] = $row;
}

jsonResponse($devices);
?>