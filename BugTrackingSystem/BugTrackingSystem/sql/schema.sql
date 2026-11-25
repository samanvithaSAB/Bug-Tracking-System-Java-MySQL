CREATE DATABASE IF NOT EXISTS bug_tracking_system;
USE bug_tracking_system;

CREATE TABLE IF NOT EXISTS users (
  id INT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(50) NOT NULL UNIQUE,
  password VARCHAR(100) NOT NULL,
  full_name VARCHAR(100) NOT NULL,
  role ENUM('ADMIN','DEVELOPER','TESTER') NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS projects (
  id INT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(120) NOT NULL UNIQUE,
  description TEXT,
  start_date DATE,
  end_date DATE,
  status ENUM('PLANNED','ACTIVE','COMPLETED','ON_HOLD') DEFAULT 'ACTIVE',
  created_by INT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (created_by) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS bugs (
  id INT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(180) NOT NULL,
  description TEXT NOT NULL,
  severity ENUM('LOW','MEDIUM','HIGH','CRITICAL') DEFAULT 'MEDIUM',
  priority ENUM('LOW','MEDIUM','HIGH','URGENT') DEFAULT 'MEDIUM',
  status ENUM('OPEN','IN_PROGRESS','RESOLVED','CLOSED','REOPENED') DEFAULT 'OPEN',
  project_id INT NOT NULL,
  reporter_id INT NOT NULL,
  developer_id INT NULL,
  reported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  resolved_at TIMESTAMP NULL,
  FOREIGN KEY (project_id) REFERENCES projects(id),
  FOREIGN KEY (reporter_id) REFERENCES users(id),
  FOREIGN KEY (developer_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS bug_comments (
  id INT AUTO_INCREMENT PRIMARY KEY,
  bug_id INT NOT NULL,
  user_id INT NOT NULL,
  comment_text TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (bug_id) REFERENCES bugs(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS activity_logs (
  id INT AUTO_INCREMENT PRIMARY KEY,
  bug_id INT NULL,
  project_id INT NULL,
  actor_id INT NOT NULL,
  action VARCHAR(80) NOT NULL,
  details TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (bug_id) REFERENCES bugs(id) ON DELETE SET NULL,
  FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE SET NULL,
  FOREIGN KEY (actor_id) REFERENCES users(id)
);

-- Demo accounts are created automatically on first application launch.
-- Change these passwords before using the application outside a classroom demo.
