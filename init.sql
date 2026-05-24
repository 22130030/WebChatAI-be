CREATE DATABASE IF NOT EXISTS appchat_db;
USE appchat_db;

CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    status VARCHAR(50) DEFAULT 'OFFLINE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS pending_conversations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    from_username VARCHAR(255) NOT NULL,
    to_username VARCHAR(255) NOT NULL,
    status VARCHAR(50) DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY unique_pending (from_username, to_username)
);

CREATE TABLE IF NOT EXISTS chat_themes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user1 VARCHAR(255) NOT NULL,
    user2 VARCHAR(255) NOT NULL,
    theme_id VARCHAR(100) DEFAULT 'DEFAULT',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY unique_chat_theme (user1, user2)
);

CREATE TABLE IF NOT EXISTS group_themes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_name VARCHAR(255) NOT NULL UNIQUE,
    theme_id VARCHAR(100) DEFAULT 'DEFAULT',
    last_changed_by VARCHAR(255),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS rooms (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS room_members (
    room_name VARCHAR(255) NOT NULL,
    username VARCHAR(255) NOT NULL,
    PRIMARY KEY (room_name, username)
);

CREATE TABLE IF NOT EXISTS messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    type VARCHAR(50) NOT NULL, -- 'people' or 'room'
    sender VARCHAR(255) NOT NULL,
    receiver VARCHAR(255) NOT NULL, -- user or room name
    content TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Mock Data
INSERT IGNORE INTO users (username, password, status) VALUES 
('admin', '123456', 'OFFLINE'),
('user1', '123456', 'OFFLINE'),
('user2', '123456', 'OFFLINE');

INSERT IGNORE INTO rooms (name) VALUES ('DevTeam'), ('General');

INSERT IGNORE INTO room_members (room_name, username) VALUES 
('DevTeam', 'admin'),
('DevTeam', 'user1'),
('General', 'admin'),
('General', 'user2');

INSERT IGNORE INTO chat_themes (user1, user2, theme_id) VALUES ('admin', 'user1', 'OCEAN_BLUE');
INSERT IGNORE INTO group_themes (group_name, theme_id, last_changed_by) VALUES ('DevTeam', 'DARK_MODE', 'admin');
