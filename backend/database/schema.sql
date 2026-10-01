-- Schema của database iot_healthcare (chỉ cấu trúc bảng, không kèm dữ liệu).
-- Import: phpMyAdmin → Import, hoặc: mysql -u root < schema.sql

-- phpMyAdmin SQL Dump
-- version 5.2.1
-- https://www.phpmyadmin.net/
--
-- Host: 127.0.0.1
-- Server version: 10.4.32-MariaDB
-- PHP Version: 8.0.30

SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
START TRANSACTION;
SET time_zone = "+00:00";


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;

--
-- Database: `iot_healthcare`
--
CREATE DATABASE IF NOT EXISTS `iot_healthcare` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `iot_healthcare`;

-- --------------------------------------------------------

--
-- Table structure for table `devices`
--

DROP TABLE IF EXISTS `devices`;
CREATE TABLE `devices` (
  `id` int(11) NOT NULL,
  `device_id` varchar(100) NOT NULL,
  `registered_by` int(11) DEFAULT NULL,
  `active` tinyint(1) DEFAULT 1,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- --------------------------------------------------------

--
-- Table structure for table `medicine_history`
--

DROP TABLE IF EXISTS `medicine_history`;
CREATE TABLE `medicine_history` (
  `id` int(11) NOT NULL,
  `schedule_id` int(11) NOT NULL,
  `user_id` int(11) NOT NULL,
  `action` enum('taken','skipped') NOT NULL,
  `action_date` date NOT NULL,
  `action_at` datetime NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- --------------------------------------------------------

--
-- Table structure for table `medicine_schedule`
--

DROP TABLE IF EXISTS `medicine_schedule`;
CREATE TABLE `medicine_schedule` (
  `id` int(11) NOT NULL,
  `user_id` int(11) DEFAULT NULL,
  `medicine_name` varchar(100) NOT NULL,
  `schedule_time` time NOT NULL,
  `note` text DEFAULT NULL,
  `repeat_daily` tinyint(1) DEFAULT 1,
  `last_action` enum('taken','skipped') DEFAULT NULL,
  `last_action_at` datetime DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- --------------------------------------------------------

--
-- Table structure for table `sensor_log_ble`
--

DROP TABLE IF EXISTS `sensor_log_ble`;
CREATE TABLE `sensor_log_ble` (
  `id` int(11) NOT NULL,
  `user_id` int(11) DEFAULT NULL,
  `device_id` varchar(100) DEFAULT NULL,
  `heart_rate` float DEFAULT NULL,
  `spo2` float DEFAULT NULL,
  `temperature` float DEFAULT NULL,
  `source` varchar(20) DEFAULT 'BLE',
  `timestamp` timestamp NOT NULL DEFAULT current_timestamp()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- --------------------------------------------------------

--
-- Table structure for table `sensor_log_http`
--

DROP TABLE IF EXISTS `sensor_log_http`;
CREATE TABLE `sensor_log_http` (
  `id` int(11) NOT NULL,
  `user_id` int(11) DEFAULT NULL,
  `device_id` varchar(100) DEFAULT NULL,
  `heart_rate` float DEFAULT NULL,
  `spo2` float DEFAULT NULL,
  `temperature` float DEFAULT NULL,
  `source` varchar(20) DEFAULT 'HTTP_ESP32',
  `timestamp` timestamp NOT NULL DEFAULT current_timestamp()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- --------------------------------------------------------

--
-- Table structure for table `sensor_log_mqtt`
--

DROP TABLE IF EXISTS `sensor_log_mqtt`;
CREATE TABLE `sensor_log_mqtt` (
  `id` int(11) NOT NULL,
  `user_id` int(11) DEFAULT NULL,
  `device_id` varchar(100) DEFAULT NULL,
  `heart_rate` float DEFAULT NULL,
  `spo2` float DEFAULT NULL,
  `temperature` float DEFAULT NULL,
  `topic` varchar(50) DEFAULT NULL,
  `source` varchar(20) DEFAULT 'MQTT',
  `timestamp` timestamp NOT NULL DEFAULT current_timestamp()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- --------------------------------------------------------

--
-- Table structure for table `users`
--

DROP TABLE IF EXISTS `users`;
CREATE TABLE `users` (
  `id` int(11) NOT NULL,
  `username` varchar(50) NOT NULL,
  `password_hash` varchar(255) NOT NULL,
  `role` enum('admin','guest') DEFAULT 'guest',
  `status` enum('active','pending') DEFAULT 'active',
  `created_by` int(11) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


--
-- Indexes for dumped tables
--

--
-- Indexes for table `devices`
--
ALTER TABLE `devices`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `device_id` (`device_id`),
  ADD KEY `idx_device_id` (`device_id`),
  ADD KEY `registered_by` (`registered_by`);

--
-- Indexes for table `medicine_history`
--
ALTER TABLE `medicine_history`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `uniq_schedule_date` (`schedule_id`,`action_date`),
  ADD KEY `idx_hist_user` (`user_id`),
  ADD KEY `idx_hist_schedule` (`schedule_id`);

--
-- Indexes for table `medicine_schedule`
--
ALTER TABLE `medicine_schedule`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_med_user` (`user_id`);

--
-- Indexes for table `sensor_log_ble`
--
ALTER TABLE `sensor_log_ble`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_ble_user_time` (`user_id`,`timestamp`),
  ADD KEY `idx_ble_device` (`device_id`);

--
-- Indexes for table `sensor_log_http`
--
ALTER TABLE `sensor_log_http`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_user_time` (`user_id`,`timestamp`),
  ADD KEY `idx_device` (`device_id`);

--
-- Indexes for table `sensor_log_mqtt`
--
ALTER TABLE `sensor_log_mqtt`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_mqtt_device` (`device_id`),
  ADD KEY `idx_mqtt_time` (`timestamp`);

--
-- Indexes for table `users`
--
ALTER TABLE `users`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `username` (`username`),
  ADD KEY `idx_username` (`username`),
  ADD KEY `idx_role` (`role`);

--
-- AUTO_INCREMENT for dumped tables
--

--
-- AUTO_INCREMENT for table `devices`
--
ALTER TABLE `devices`
  MODIFY `id` int(11) NOT NULL AUTO_INCREMENT;

--
-- AUTO_INCREMENT for table `medicine_history`
--
ALTER TABLE `medicine_history`
  MODIFY `id` int(11) NOT NULL AUTO_INCREMENT;

--
-- AUTO_INCREMENT for table `medicine_schedule`
--
ALTER TABLE `medicine_schedule`
  MODIFY `id` int(11) NOT NULL AUTO_INCREMENT;

--
-- AUTO_INCREMENT for table `sensor_log_ble`
--
ALTER TABLE `sensor_log_ble`
  MODIFY `id` int(11) NOT NULL AUTO_INCREMENT;

--
-- AUTO_INCREMENT for table `sensor_log_http`
--
ALTER TABLE `sensor_log_http`
  MODIFY `id` int(11) NOT NULL AUTO_INCREMENT;

--
-- AUTO_INCREMENT for table `sensor_log_mqtt`
--
ALTER TABLE `sensor_log_mqtt`
  MODIFY `id` int(11) NOT NULL AUTO_INCREMENT;

--
-- AUTO_INCREMENT for table `users`
--
ALTER TABLE `users`
  MODIFY `id` int(11) NOT NULL AUTO_INCREMENT;

--
-- Constraints for dumped tables
--

--
-- Constraints for table `devices`
--
ALTER TABLE `devices`
  ADD CONSTRAINT `devices_ibfk_1` FOREIGN KEY (`registered_by`) REFERENCES `users` (`id`) ON DELETE SET NULL;

--
-- Constraints for table `medicine_history`
--
ALTER TABLE `medicine_history`
  ADD CONSTRAINT `medicine_history_ibfk_1` FOREIGN KEY (`schedule_id`) REFERENCES `medicine_schedule` (`id`) ON DELETE CASCADE,
  ADD CONSTRAINT `medicine_history_ibfk_2` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;

--
-- Constraints for table `medicine_schedule`
--
ALTER TABLE `medicine_schedule`
  ADD CONSTRAINT `medicine_schedule_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;

--
-- Constraints for table `sensor_log_http`
--
ALTER TABLE `sensor_log_http`
  ADD CONSTRAINT `sensor_log_http_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL;
COMMIT;

/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
