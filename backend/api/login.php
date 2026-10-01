<?php
// ============================================================
// API: POST /healthcare/api/login.php
// Body: {"username": "admin", "password": "<your-password>"}
// Response: {"token": "xxx", "role": "admin", "userId": 1}
// KHÔNG cần token để gọi API này
// ============================================================
require_once __DIR__ . '/../config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    errorResponse("Method not allowed", 405);
}

$body = getJsonBody();
$username = isset($body['username']) ? trim($body['username']) : '';
$password = isset($body['password']) ? $body['password'] : '';

if (empty($username) || empty($password)) {
    errorResponse("Username and password are required", 400);
}

// Tìm user trong database
$stmt = $conn->prepare("SELECT id, username, password_hash, role, status FROM users WHERE username = ?");
$stmt->bind_param("s", $username);
$stmt->execute();
$result = $stmt->get_result();

if ($result->num_rows === 0) {
    errorResponse("Invalid username or password", 401);
}

$user = $result->fetch_assoc();
$stmt->close();

// Kiểm tra tài khoản bị khóa
if ($user['status'] !== 'active') {
    errorResponse("Account is not active", 403);
}

// Verify mật khẩu
if (!password_verify($password, $user['password_hash'])) {
    errorResponse("Invalid username or password", 401);
}

// Tạo JWT token
$token = createJWT([
    'userId' => (int)$user['id'],
    'role'   => $user['role']
]);

// Trả response
jsonResponse([
    'token'  => $token,
    'role'   => $user['role'],
    'userId' => (int)$user['id']
]);
?>