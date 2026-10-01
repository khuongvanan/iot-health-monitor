<?php
// ============================================================
// API: DELETE /healthcare/api/medicine_delete.php?id=1
// Header: Authorization: Bearer <token>
// Response: {"message": "Schedule deleted"}
// ============================================================
require_once __DIR__ . '/../config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'DELETE') {
    errorResponse("Method not allowed", 405);
}

$auth = requireAuth();

$id = isset($_GET['id']) ? (int)$_GET['id'] : 0;

if ($id <= 0) {
    errorResponse("Valid schedule ID is required", 400);
}

// Kiểm tra quyền: admin xóa bất kỳ, user chỉ xóa của mình
if ($auth['role'] !== 'admin') {
    $check = $conn->prepare("SELECT user_id FROM medicine_schedule WHERE id = ?");
    $check->bind_param("i", $id);
    $check->execute();
    $row = $check->get_result()->fetch_assoc();
    $check->close();

    if (!$row || (int)$row['user_id'] !== $auth['userId']) {
        errorResponse("Not authorized to delete this schedule", 403);
    }
}

$stmt = $conn->prepare("DELETE FROM medicine_schedule WHERE id = ?");
$stmt->bind_param("i", $id);

if ($stmt->execute()) {
    if ($stmt->affected_rows > 0) {
        $stmt->close();
        jsonResponse(["message" => "Schedule deleted"]);
    } else {
        $stmt->close();
        errorResponse("Schedule not found", 404);
    }
} else {
    $stmt->close();
    errorResponse("Failed to delete schedule", 500);
}
?>