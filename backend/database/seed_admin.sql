-- Tạo tài khoản admin mặc định.
-- Bước 1: php backend/tools/gen_hash.php "mat-khau-cua-ban"
-- Bước 2: thay <BCRYPT_HASH> bên dưới bằng hash vừa tạo rồi import file này.
USE iot_healthcare;

DELETE FROM users WHERE username = 'admin';

INSERT INTO users (username, password_hash, role, status)
VALUES ('admin', '<BCRYPT_HASH>', 'admin', 'active');

SELECT id, username, role, status FROM users WHERE username = 'admin';
