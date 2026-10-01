<?php
// ============================================================
// API: DELETE /healthcare/api/users_delete.php?id=2
// Header: Authorization: Bearer <token>
// CHỈ ADMIN mới được xóa, KHÔNG xóa được chính mình
// ============================================================
require_once __DIR__ . '/../config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'DELETE') {
    errorResponse("Method not allowed", 405);
}

$auth = requireAuth();

if ($auth['role'] !== 'admin') {
    errorResponse("Admin only", 403);
}

$deleteId = isset($_GET['id']) ? (int)$_GET['id'] : 0;

if ($deleteId <= 0) {
    errorResponse("Valid user ID is required", 400);
}

// Không cho admin tự xóa chính mình
if ($deleteId === $auth['userId']) {
    errorResponse("Cannot delete your own account", 400);
}

$stmt = $conn->prepare("DELETE FROM users WHERE id = ?");
$stmt->bind_param("i", $deleteId);

if ($stmt->execute()) {
    if ($stmt->affected_rows > 0) {
        $stmt->close();
        jsonResponse(["message" => "User deleted successfully"]);
    } else {
        $stmt->close();
        errorResponse("User not found", 404);
    }
} else {
    $stmt->close();
    errorResponse("Failed to delete user", 500);
}
?>