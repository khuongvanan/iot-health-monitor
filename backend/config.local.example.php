<?php
// Copy file này thành config.local.php rồi điền giá trị thật.
// config.local.php đã được .gitignore bỏ qua.
$db_host = "localhost";
$db_name = "iot_healthcare";
$db_user = "root";
$db_pass = "";

// Chuỗi bí mật ký JWT — đặt một chuỗi ngẫu nhiên dài, KHÔNG dùng lại chuỗi mẫu này.
define('JWT_SECRET', 'change-me-to-a-long-random-string');
