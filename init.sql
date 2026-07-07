CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    display_name VARCHAR(100),
    avatar TEXT,
    bio VARCHAR(500),
    status VARCHAR(50) DEFAULT 'OFFLINE',
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Add role column if upgrading existing DB
ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'USER';

CREATE TABLE IF NOT EXISTS pending_conversations (
    id BIGSERIAL PRIMARY KEY,
    from_username VARCHAR(255) NOT NULL,
    to_username VARCHAR(255) NOT NULL,
    status VARCHAR(50) DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_pending UNIQUE (from_username, to_username)
);

CREATE TABLE IF NOT EXISTS friendships (
    id BIGSERIAL PRIMARY KEY,
    user_low VARCHAR(255) NOT NULL,
    user_high VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_friendship UNIQUE (user_low, user_high),
    CONSTRAINT friendship_not_self CHECK (user_low <> user_high)
);

INSERT INTO friendships (user_low, user_high, created_at)
SELECT
    LEAST(from_username, to_username),
    GREATEST(from_username, to_username),
    COALESCE(updated_at, created_at, CURRENT_TIMESTAMP)
FROM pending_conversations
WHERE status = 'ACCEPTED'
ON CONFLICT (user_low, user_high) DO NOTHING;

DELETE FROM pending_conversations
WHERE status IN ('ACCEPTED', 'REMOVED');

CREATE TABLE IF NOT EXISTS chat_themes (
    id BIGSERIAL PRIMARY KEY,
    user1 VARCHAR(255) NOT NULL,
    user2 VARCHAR(255) NOT NULL,
    theme_id VARCHAR(100) DEFAULT 'DEFAULT',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_chat_theme UNIQUE (user1, user2)
);

CREATE TABLE IF NOT EXISTS group_themes (
    id BIGSERIAL PRIMARY KEY,
    group_name VARCHAR(255) NOT NULL UNIQUE,
    theme_id VARCHAR(100) DEFAULT 'DEFAULT',
    last_changed_by VARCHAR(255),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS rooms (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    type VARCHAR(20) NOT NULL DEFAULT 'room',
    owner_username VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS room_members (
    room_name VARCHAR(255) NOT NULL,
    username VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    PRIMARY KEY (room_name, username)
);

CREATE TABLE IF NOT EXISTS chat_summaries (
    id BIGSERIAL PRIMARY KEY,
    conversation_type VARCHAR(30) NOT NULL,
    target VARCHAR(255) NOT NULL,
    summary_mode VARCHAR(30) NOT NULL,
    period_type VARCHAR(30) NOT NULL,
    from_time TIMESTAMP,
    to_time TIMESTAMP,
    message_limit INTEGER,
    message_count INTEGER,
    last_message_id VARCHAR(64),
    summary_text TEXT NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    ai_provider VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chat_summary_lookup
    ON chat_summaries (conversation_type, target, summary_mode, period_type, created_by);

CREATE INDEX IF NOT EXISTS idx_chat_summary_last_message
    ON chat_summaries (last_message_id);

-- =========================================================
-- Sample data
-- =========================================================
-- Dữ liệu mẫu chỉ thuộc tài khoản sa / Mk123456.
-- Tài khoản admin / 123456 là admin hệ thống và không được gắn dữ liệu mẫu.
-- User mới đăng ký chỉ có record users, không tự được gắn contact/room/theme mẫu.

DELETE FROM chat_summaries
WHERE ai_provider = 'seed';

DELETE FROM pending_conversations
WHERE from_username IN ('admin', 'user1', 'user2', 'linh', 'minh', 'an', 'bao', 'chi', 'duy', 'ha')
   OR to_username IN ('admin', 'user1', 'user2', 'linh', 'minh', 'an', 'bao', 'chi', 'duy', 'ha');

DELETE FROM friendships
WHERE user_low IN ('admin', 'user1', 'user2', 'linh', 'minh', 'an', 'bao', 'chi', 'duy', 'ha')
   OR user_high IN ('admin', 'user1', 'user2', 'linh', 'minh', 'an', 'bao', 'chi', 'duy', 'ha');

DELETE FROM chat_themes
WHERE user1 IN ('admin', 'user1', 'user2', 'linh', 'minh', 'an', 'bao', 'chi', 'duy', 'ha')
   OR user2 IN ('admin', 'user1', 'user2', 'linh', 'minh', 'an', 'bao', 'chi', 'duy', 'ha');

DELETE FROM group_themes
WHERE group_name IN ('DevTeam', 'General', 'Design', 'Marketing', 'QA', 'Backend', 'Frontend', 'Mobile', 'Support', 'Product');

DELETE FROM room_members
WHERE room_name IN ('DevTeam', 'General', 'Design', 'Marketing', 'QA', 'Backend', 'Frontend', 'Mobile', 'Support', 'Product')
   OR username IN ('admin', 'user1', 'user2', 'linh', 'minh', 'an', 'bao', 'chi', 'duy', 'ha');

DELETE FROM rooms
WHERE name IN ('DevTeam', 'General', 'Design', 'Marketing', 'QA', 'Backend', 'Frontend', 'Mobile', 'Support', 'Product');

DELETE FROM users
WHERE username IN ('user1', 'user2', 'linh', 'minh', 'an', 'bao', 'chi', 'duy', 'ha');

INSERT INTO users (username, password, display_name, status, role)
VALUES
    ('sa', 'Mk123456.', 'Sample Account', 'OFFLINE', 'USER'),
    ('admin', '123456', 'admin', 'OFFLINE', 'ADMIN')
ON CONFLICT (username) DO UPDATE SET
    password = EXCLUDED.password,
    display_name = EXCLUDED.display_name,
    role = EXCLUDED.role;

INSERT INTO rooms (name, type, owner_username)
VALUES
    ('SA-General', 'room', 'sa'),
    ('SA-DevTeam', 'room', 'sa'),
    ('SA-Support', 'room', 'sa')
ON CONFLICT (name) DO UPDATE SET
    owner_username = EXCLUDED.owner_username;

INSERT INTO room_members (room_name, username, role)
VALUES
    ('SA-General', 'sa', 'OWNER'),
    ('SA-DevTeam', 'sa', 'OWNER'),
    ('SA-Support', 'sa', 'OWNER')
ON CONFLICT (room_name, username) DO UPDATE SET
    role = EXCLUDED.role;

INSERT INTO group_themes (group_name, theme_id, last_changed_by)
VALUES
    ('SA-General', 'OCEAN_BLUE', 'sa'),
    ('SA-DevTeam', 'DARK_MODE', 'sa'),
    ('SA-Support', 'CLASSIC', 'sa')
ON CONFLICT (group_name) DO UPDATE SET
    theme_id = EXCLUDED.theme_id,
    last_changed_by = EXCLUDED.last_changed_by,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO chat_summaries (
    conversation_type,
    target,
    summary_mode,
    period_type,
    from_time,
    to_time,
    message_limit,
    message_count,
    last_message_id,
    summary_text,
    created_by,
    ai_provider
)
SELECT *
FROM (
    VALUES
        ('room', 'SA-General', 'general', 'latest', NULL::timestamp, NULL::timestamp, 100, 12, 'seed-sa-msg-001', 'Khu vực trao đổi chung của tài khoản sa.', 'sa', 'seed'),
        ('room', 'SA-DevTeam', 'tasks', 'latest', NULL::timestamp, NULL::timestamp, 100, 8, 'seed-sa-msg-002', 'Checklist mẫu cho nhóm phát triển thuộc tài khoản sa.', 'sa', 'seed'),
        ('room', 'SA-Support', 'general', 'today', CURRENT_DATE::timestamp, (CURRENT_DATE + INTERVAL '1 day')::timestamp, 100, 6, 'seed-sa-msg-003', 'Ghi chú hỗ trợ mẫu thuộc tài khoản sa.', 'sa', 'seed')
) AS seed(
    conversation_type,
    target,
    summary_mode,
    period_type,
    from_time,
    to_time,
    message_limit,
    message_count,
    last_message_id,
    summary_text,
    created_by,
    ai_provider
)
WHERE NOT EXISTS (
    SELECT 1
    FROM chat_summaries existing
    WHERE existing.last_message_id = seed.last_message_id
);
