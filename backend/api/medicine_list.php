<?php
// ============================================================
// FILE: healthcare/api/medicine_list.php   (VERSION 4)
// THAY ĐỔI: đọc history từ bảng medicine_history (JOIN bằng PHP)
// ============================================================
require_once __DIR__ . '/../config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    errorResponse("Method not allowed", 405);
}

$auth = requireAuth();
$userId = isset($_GET['userId']) ? (int)$_GET['userId'] : $auth['userId'];

if ($auth['role'] !== 'admin' && $userId !== (int)$auth['userId']) {
    errorResponse("You can only view your own medicine schedule", 403);
}

// ── 1) Lấy danh sách lịch ─────────────────────────────────────
$stmt = $conn->prepare(
    "SELECT id,
            user_id         AS userId,
            medicine_name   AS medicineName,
            schedule_time   AS scheduleTime,
            note,
            repeat_daily    AS repeatDaily,
            last_action     AS lastAction,
            last_action_at  AS lastActionAt
     FROM medicine_schedule
     WHERE user_id = ?
     ORDER BY schedule_time ASC"
);
$stmt->bind_param("i", $userId);
$stmt->execute();
$result = $stmt->get_result();

$today      = date('Y-m-d');
$schedules  = [];
$scheduleIds = [];

while ($row = $result->fetch_assoc()) {
    $row['id']          = (int)$row['id'];
    $row['userId']      = (int)$row['userId'];
    $row['repeatDaily'] = (bool)$row['repeatDaily'];
    $row['history']       = [];
    $row['todayAction']   = null;
    $row['todayActionAt'] = null;
    $schedules[$row['id']] = $row;
    $scheduleIds[] = $row['id'];
}
$stmt->close();

// ── 2) Lấy toàn bộ history (1 query duy nhất) ─────────────────
if (!empty($scheduleIds)) {
    $placeholders = implode(',', array_fill(0, count($scheduleIds), '?'));
    $types        = str_repeat('i', count($scheduleIds));

    $sql = "SELECT schedule_id,
                   action,
                   DATE_FORMAT(action_date, '%Y-%m-%d')           AS date,
                   DATE_FORMAT(action_at,   '%Y-%m-%d %H:%i:%s') AS at
            FROM medicine_history
            WHERE schedule_id IN ($placeholders)
            ORDER BY action_date ASC";

    $h = $conn->prepare($sql);
    $h->bind_param($types, ...$scheduleIds);
    $h->execute();
    $hr = $h->get_result();

    while ($e = $hr->fetch_assoc()) {
        $sid = (int)$e['schedule_id'];
        unset($e['schedule_id']);
        if (isset($schedules[$sid])) {
            $schedules[$sid]['history'][] = $e;
            if ($e['date'] === $today) {
                $schedules[$sid]['todayAction']   = $e['action'];
                $schedules[$sid]['todayActionAt'] = $e['at'];
            }
        }
    }
    $h->close();
}

jsonResponse(array_values($schedules));
?>