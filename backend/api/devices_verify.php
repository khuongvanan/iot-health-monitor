<?php
// ============================================================
// API: GET /healthcare/api/devices_verify.php?deviceId=ESP32_H1
// Header: Authorization: Bearer <token>
// Response: true hoặc false
// ============================================================
require_once __DIR__ . '/../config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    errorResponse("Method not allowed", 405);
}

requireAuth();

$deviceId = isset($_GET['deviceId']) ? trim($_GET['deviceId']) : '';

if (empty($deviceId)) {
    errorResponse("Device ID is required", 400);
}

$stmt = $conn->prepare("SELECT id FROM devices WHERE device_id = ? AND active = TRUE");
$stmt->bind_param("s", $deviceId);
$stmt->execute();
$result = $stmt->get_result();

$exists = $result->num_rows > 0;
$stmt->close();

jsonResponse($exists);
?>