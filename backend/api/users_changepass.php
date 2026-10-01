<?php
// ============================================================
// API: PUT /healthcare/api/users_changepass.php
// Header: Authorization: Bearer <token>
// Body: {"userId": "1", "oldPassword": "xxx", "newPassword": "yyy"}
// Response: {"message": "Password changed successfully"}
// ============================================================
require_once __DIR__ . '/../config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'PUT') {
    errorResponse("Method not allowed", 405);
}

$auth = requireAuth();

$body       = getJsonBody();
$userId     = isset($body['userId'])      ? (int)$body['userId']      : $auth['userId'];
$oldPass    = isset($body['oldPassword']) ? $body['oldPassword']      : '';
$newPass    = isset($body['newPassword']) ? $body['newPassword']      : '';

if (empty($oldPass) || empty($newPass)) {
    errorResponse("Old and new passwords are required", 400);
}

if (strlen($newPass) < 6) {
    errorResponse("New password must be at least 6 characters", 400);
}

// Lấy hash hiện tại
$stmt = $conn->prepare("SELECT password_hash FROM users WHERE id = ?");
$stmt->bind_param("i", $userId);
$stmt->execute();
$result = $stmt->get_result();

if ($result->num_rows === 0) {
    $stmt->close();
    errorResponse("User not found", 404);
}

$user = $result->fetch_assoc();
$stmt->close();

// Verify mật khẩu cũ
if (!password_verify($oldPass, $user['password_hash'])) {
    errorResponse("Old password is incorrect", 401);
}

// Cập nhật mật khẩu mới
$newHash = password_hash($newPass, PASSWORD_BCRYPT);
$update = $conn->prepare("UPDATE users SET password_hash = ? WHERE id = ?");
$update->bind_param("si", $newHash, $userId);

if ($update->execute()) {
    $update->close();
    jsonResponse(["message" => "Password changed successfully"]);
} else {
    $update->close();
    errorResponse("Failed to change password", 500);
}
?>