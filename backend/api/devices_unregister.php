<?php
// ============================================================
// API: DELETE /healthcare/api/devices_unregister.php?deviceId=ESP32_H1
// Header: Authorization: Bearer <token>
//
// Mark thiết bị thành active=FALSE (soft delete). KHÔNG xóa record
// để các sensor_log lịch sử tham chiếu device_id vẫn còn ngữ cảnh.
//
// Chỉ admin được phép gọi.
//
// Response: {"success":true, "deviceId":"ESP32_H1", "affected":1}
// ============================================================
require_once __DIR__ . '/../config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'DELETE') {
    errorResponse("Method not allowed", 405);
}

$auth = requireAuth();
if (!isset($auth['role']) || $auth['role'] !== 'admin') {
    errorResponse("Chỉ admin được phép unregister thiết bị", 403);
}

$deviceId = isset($_GET['deviceId']) ? trim($_GET['deviceId']) : '';
if (empty($deviceId)) {
    errorResponse("deviceId is required", 400);
}

$stmt = $conn->prepare("UPDATE devices SET active = FALSE WHERE device_id = ?");
$stmt->bind_param("s", $deviceId);

if ($stmt->execute()) {
    $affected = $stmt->affected_rows;
    $stmt->close();
    jsonResponse([
        'success'  => true,
        'deviceId' => $deviceId,
        'affected' => $affected
    ]);
} else {
    $stmt->close();
    errorResponse("Failed to unregister device", 500);
}
?>