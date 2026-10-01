<?php
// ============================================================
// API: POST /healthcare/api/users_create.php
// Header: Authorization: Bearer <token>
// Body: {"username": "nguyen", "passwordHash": "123456", "role": "guest"}
// Response: {"id": 2, "username": "nguyen", ...}
// CHỈ ADMIN mới được gọi
// ============================================================
require_once __DIR__ . '/../config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    errorResponse("Method not allowed", 405);
}

$auth = requireAuth();

// Kiểm tra quyền admin
if ($auth['role'] !== 'admin') {
    errorResponse("Admin only", 403);
}

$body = getJsonBody();
$username = isset($body['username'])     ? trim($body['username'])     : '';
$password = isset($body['passwordHash']) ? $body['passwordHash']       : '';
$role     = isset($body['role'])         ? $body['role']               : 'guest';
$status   = isset($body['status'])       ? $body['status']             : 'active';

if (empty($username) || empty($password)) {
    errorResponse("Username and password are required", 400);
}

if (!in_array($role, ['admin', 'guest'])) {
    $role = 'guest';
}

// Kiểm tra username đã tồn tại
$check = $conn->prepare("SELECT id FROM users WHERE username = ?");
$check->bind_param("s", $username);
$check->execute();
if ($check->get_result()->num_rows > 0) {
    $check->close();
    errorResponse("Username already exists", 409);
}
$check->close();

// Hash mật khẩu
$hash = password_hash($password, PASSWORD_BCRYPT);

// Insert
$stmt = $conn->prepare(
    "INSERT INTO users (username, password_hash, role, status, created_by) VALUES (?, ?, ?, ?, ?)"
);
$createdBy = $auth['userId'];
$stmt->bind_param("ssssi", $username, $hash, $role, $status, $createdBy);

if ($stmt->execute()) {
    $newId = $stmt->insert_id;
    $stmt->close();
    jsonResponse([
        'id'        => $newId,
        'username'  => $username,
        'role'      => $role,
        'status'    => $status,
        'createdBy' => $createdBy
    ], 201);
} else {
    $stmt->close();
    errorResponse("Failed to create user", 500);
}
?>