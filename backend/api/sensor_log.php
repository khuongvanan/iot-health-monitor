<?php
// ============================================================
// API: POST /healthcare/api/sensor_log.php
// Header: Authorization: Bearer <token>
// Body: {
//   "heartRate": 75.0,
//   "spo2": 98.0,
//   "temperature": 36.5,
//   "deviceId": "ESP32_H1",
//   "userId": "1",
//   "source": "BLE" | "BLE_SYNC" | "MQTT"
// }
//
// Route theo source:
//   BLE / BLE_SYNC  → sensor_log_ble
//   MQTT            → sensor_log_mqtt
//   (HTTP_ESP32 đi qua esp32_log.php → sensor_log_http)
// ============================================================
require_once __DIR__ . '/../config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    errorResponse("Method not allowed", 405);
}

$auth = requireAuth();
$body = getJsonBody();

$userId      = isset($body['userId'])      ? (int)$body['userId']        : $auth['userId'];
$deviceId    = isset($body['deviceId'])    ? trim($body['deviceId'])     : '';
$heartRate   = isset($body['heartRate'])   ? (float)$body['heartRate']   : 0.0;
$spo2        = isset($body['spo2'])        ? (float)$body['spo2']        : 0.0;
$temperature = isset($body['temperature']) ? (float)$body['temperature'] : 0.0;
$source      = isset($body['source'])      ? trim($body['source'])       : '';

// Validate cơ bản
if ($heartRate <= 0 && $spo2 <= 0 && $temperature <= 0) {
    errorResponse("At least one sensor value must be > 0", 400);
}

// ── Chọn bảng theo source ──────────────────────────────────
$src = strtoupper($source);
switch ($src) {
    case 'BLE':
    case 'BLE_SYNC':
        $sql = "INSERT INTO sensor_log_ble
                  (user_id, device_id, heart_rate, spo2, temperature, source)
                VALUES (?, ?, ?, ?, ?, ?)";
        break;

    case 'MQTT':
        // sensor_log_mqtt sau ALTER có thêm user_id và source
        $sql = "INSERT INTO sensor_log_mqtt
                  (user_id, device_id, heart_rate, spo2, temperature, source)
                VALUES (?, ?, ?, ?, ?, ?)";
        break;

    default:
        // Source lạ / rỗng → fallback vào sensor_log_ble để không mất data
        $src = $src ?: 'UNKNOWN';
        $sql = "INSERT INTO sensor_log_ble
                  (user_id, device_id, heart_rate, spo2, temperature, source)
                VALUES (?, ?, ?, ?, ?, ?)";
        break;
}

$stmt = $conn->prepare($sql);
$stmt->bind_param("isddds", $userId, $deviceId, $heartRate, $spo2, $temperature, $src);

if ($stmt->execute()) {
    $newId = $stmt->insert_id;
    $stmt->close();
    jsonResponse([
        "message" => "Sensor data logged",
        "id"      => $newId,
        "source"  => $src
    ], 201);
} else {
    $stmt->close();
    errorResponse("Failed to log sensor data", 500);
}
?>