<?php
// ============================================================
// API: POST /healthcare/api/devices_register.php
// Header: Authorization: Bearer <token>
// Body: {"deviceId": "ESP32_H1"}
// Response: {"id": 1, "deviceId": "ESP32_H1", ...}
//
// CHÚ Ý: Nếu device_id đã có sẵn trong DB nhưng active=FALSE
// (đã bị unregister trước đó) → REACTIVATE lại (set active=TRUE
// và cập nhật registered_by thành user hiện tại).
// Tránh tình trạng "đăng ký giả": app báo thành công nhưng DB
// vẫn active=FALSE nên getMyDevices() trả rỗng.
// ============================================================
require_once __DIR__ . '/../config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    errorResponse("Method not allowed", 405);
}

$auth = requireAuth();
$body = getJsonBody();

$deviceId = isset($body['deviceId']) ? trim($body['deviceId']) : '';

if (empty($deviceId)) {
    errorResponse("Device ID is required", 400);
}

// Kiểm tra đã có record cho device_id này chưa
$check = $conn->prepare("SELECT id, device_id, registered_by, active FROM devices WHERE device_id = ?");
$check->bind_param("s", $deviceId);
$check->execute();
$existing = $check->get_result();

if ($existing->num_rows > 0) {
    $device = $existing->fetch_assoc();
    $check->close();

    $isActive = (bool)$device['active'];

    // Nếu đã bị unregister (active=FALSE) → reactivate + đổi chủ
    if (!$isActive) {
        $deviceRowId  = (int)$device['id'];
        $registeredBy = (int)$auth['userId'];
        $update = $conn->prepare("UPDATE devices SET active = TRUE, registered_by = ? WHERE id = ?");
        $update->bind_param("ii", $registeredBy, $deviceRowId);
        $update->execute();
        $update->close();

        jsonResponse([
            'id'           => $deviceRowId,
            'deviceId'     => $device['device_id'],
            'registeredBy' => $registeredBy,
            'active'       => true,
            'reactivated'  => true
        ]);
        exit;
    }

    // Đã active → trả về luôn (không cần làm gì)
    jsonResponse([
        'id'           => (int)$device['id'],
        'deviceId'     => $device['device_id'],
        'registeredBy' => (int)$device['registered_by'],
        'active'       => true
    ]);
    exit;
}
$check->close();

// Chưa có record → tạo mới
$registeredBy = $auth['userId'];
$stmt = $conn->prepare("INSERT INTO devices (device_id, registered_by, active) VALUES (?, ?, TRUE)");
$stmt->bind_param("si", $deviceId, $registeredBy);

if ($stmt->execute()) {
    $newId = $stmt->insert_id;
    $stmt->close();
    jsonResponse([
        'id'           => $newId,
        'deviceId'     => $deviceId,
        'registeredBy' => $registeredBy,
        'active'       => true
    ], 201);
} else {
    $stmt->close();
    errorResponse("Failed to register device", 500);
}
?>