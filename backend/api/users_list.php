<?php
// ============================================================
// API: GET /healthcare/api/users_list.php
// Header: Authorization: Bearer <token>
// Response: [{"id": 1, "username": "admin", "role": "admin", ...}, ...]
// ============================================================
require_once __DIR__ . '/../config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    errorResponse("Method not allowed", 405);
}

$auth = requireAuth();

$sql = "SELECT id, username, role, status, created_by AS createdBy, created_at AS createdAt
        FROM users ORDER BY id ASC";
$result = $conn->query($sql);

$users = [];
while ($row = $result->fetch_assoc()) {
    $row['id'] = (int)$row['id'];
    if ($row['createdBy'] !== null) $row['createdBy'] = (int)$row['createdBy'];
    $users[] = $row;
}

jsonResponse($users);
?>