<?php
// ============================================================
// API: POST /healthcare/api/medicine_save.php
// Header: Authorization: Bearer <token>
// Body: {
//   "id": 0,                (0 = thêm mới, >0 = cập nhật)
//   "userId": 2,
//   "medicineName": "Paracetamol",
//   "scheduleTime": "20:00",
//   "note": "Uống sau ăn",
//   "repeatDaily": true     ← MỚI: lặp hàng ngày hay 1 lần
// }
// ============================================================
require_once __DIR__ . '/../config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    errorResponse("Method not allowed", 405);
}

$auth = requireAuth();
$body = getJsonBody();

$id           = isset($body['id'])           ? (int)$body['id']            : 0;
$userId       = isset($body['userId'])       ? (int)$body['userId']        : $auth['userId'];
$medicineName = isset($body['medicineName']) ? trim($body['medicineName']) : '';
$scheduleTime = isset($body['scheduleTime']) ? trim($body['scheduleTime']) : '';
$note         = isset($body['note'])         ? trim($body['note'])          : '';
// ── MỚI: lặp hàng ngày, mặc định TRUE ──
$repeatDaily  = isset($body['repeatDaily'])  ? (bool)$body['repeatDaily']  : true;

if (empty($medicineName) || empty($scheduleTime)) {
    errorResponse("Medicine name and schedule time are required", 400);
}

if ($id > 0) {
    // ── Cập nhật lịch thuốc cũ ───────────────────────────────
    $stmt = $conn->prepare(
        "UPDATE medicine_schedule
         SET medicine_name = ?, schedule_time = ?, note = ?, repeat_daily = ?
         WHERE id = ?"
    );
    $repeatInt = $repeatDaily ? 1 : 0;
    $stmt->bind_param("sssii", $medicineName, $scheduleTime, $note, $repeatInt, $id);

    if ($stmt->execute()) {
        $stmt->close();
        jsonResponse([
            'id'           => $id,
            'userId'       => $userId,
            'medicineName' => $medicineName,
            'scheduleTime' => $scheduleTime,
            'note'         => $note,
            'repeatDaily'  => $repeatDaily
        ]);
    } else {
        $stmt->close();
        errorResponse("Failed to update schedule", 500);
    }
} else {
    // ── Thêm lịch thuốc mới ──────────────────────────────────
    $stmt = $conn->prepare(
        "INSERT INTO medicine_schedule (user_id, medicine_name, schedule_time, note, repeat_daily)
         VALUES (?, ?, ?, ?, ?)"
    );
    $repeatInt = $repeatDaily ? 1 : 0;
    $stmt->bind_param("isssi", $userId, $medicineName, $scheduleTime, $note, $repeatInt);

    if ($stmt->execute()) {
        $newId = $stmt->insert_id;
        $stmt->close();
        jsonResponse([
            'id'           => $newId,
            'userId'       => $userId,
            'medicineName' => $medicineName,
            'scheduleTime' => $scheduleTime,
            'note'         => $note,
            'repeatDaily'  => $repeatDaily
        ], 201);
    } else {
        $stmt->close();
        errorResponse("Failed to save schedule", 500);
    }
}
?>