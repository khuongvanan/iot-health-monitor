<?php
// ============================================================
// FILE: healthcare/api/medicine_confirm.php   (VERSION 5)
// THAY ĐỔI: dùng bảng medicine_history mới, mỗi event = 1 dòng
// ============================================================
require_once __DIR__ . '/../config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    errorResponse("Method not allowed", 405);
}

$auth = requireAuth();
$body = getJsonBody();

$id     = isset($body['id'])     ? (int)$body['id']      : 0;
$action = isset($body['action']) ? trim($body['action']) : '';

if ($id <= 0) errorResponse("Valid schedule ID is required", 400);
if (!in_array($action, ['taken', 'skipped'], true)) {
    errorResponse("Action must be 'taken' or 'skipped'", 400);
}

// ── 1) Lấy user_id của schedule + kiểm quyền ──────────────────
$sel = $conn->prepare("SELECT user_id FROM medicine_schedule WHERE id = ?");
$sel->bind_param("i", $id);
$sel->execute();
$row = $sel->get_result()->fetch_assoc();
$sel->close();

if (!$row) errorResponse("Schedule not found", 404);
$scheduleUserId = (int)$row['user_id'];
if ($auth['role'] !== 'admin' && $scheduleUserId !== (int)$auth['userId']) {
    errorResponse("Not authorized to confirm this schedule", 403);
}

$now   = date('Y-m-d H:i:s');
$today = date('Y-m-d');

// ── 2) Transaction: insert history + update schedule ──────────
$conn->begin_transaction();
try {
    $ins = $conn->prepare(
        "INSERT INTO medicine_history
            (schedule_id, user_id, action, action_date, action_at)
         VALUES (?, ?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE
            action    = VALUES(action),
            action_at = VALUES(action_at)"
    );
    $ins->bind_param("iisss", $id, $scheduleUserId, $action, $today, $now);
    if (!$ins->execute()) throw new Exception("Insert history failed: " . $ins->error);
    $wasNew = ($ins->affected_rows === 1);
    $ins->close();

    $upd = $conn->prepare(
        "UPDATE medicine_schedule
         SET last_action    = ?,
             last_action_at = ?
         WHERE id = ?
           AND (last_action_at IS NULL OR last_action_at <= ?)"
    );
    $upd->bind_param("ssis", $action, $now, $id, $now);
    if (!$upd->execute()) throw new Exception("Update schedule failed: " . $upd->error);
    $upd->close();

    $conn->commit();
    jsonResponse([
        "message"  => "Confirmed",
        "id"       => $id,
        "action"   => $action,
        "at"       => $now,
        "date"     => $today,
        "newEntry" => $wasNew
    ]);
} catch (Exception $e) {
    $conn->rollback();
    errorResponse("Failed to confirm: " . $e->getMessage(), 500);
}
?>