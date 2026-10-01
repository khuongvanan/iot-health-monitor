<?php
// Tạo bcrypt hash cho mật khẩu admin.
// Cách dùng (CLI):  php gen_hash.php "mat-khau-cua-ban"
// Sau đó dán hash vào database/seed_admin.sql
if (PHP_SAPI !== 'cli') { http_response_code(403); exit("CLI only\n"); }
if ($argc < 2) { exit("Usage: php gen_hash.php <password>\n"); }
echo password_hash($argv[1], PASSWORD_BCRYPT) . PHP_EOL;
