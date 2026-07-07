CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    display_name VARCHAR(100),
    avatar TEXT,
    bio VARCHAR(500),
    status VARCHAR(50) DEFAULT 'OFFLINE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS pending_conversations (
    id BIGSERIAL PRIMARY KEY,
    from_username VARCHAR(255) NOT NULL,
    to_username VARCHAR(255) NOT NULL,
    status VARCHAR(50) DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_pending UNIQUE (from_username, to_username)
);

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

INSERT INTO users (username, password, display_name, status)
VALUES
    ('admin', '123456', 'admin', 'OFFLINE'),
    ('user1', '123456', 'user1', 'OFFLINE'),
    ('user2', '123456', 'user2', 'OFFLINE'),
    ('linh', '123456', 'Linh Nguyen', 'OFFLINE'),
    ('minh', '123456', 'Minh Tran', 'OFFLINE'),
    ('an', '123456', 'An Le', 'OFFLINE'),
    ('bao', '123456', 'Bao Pham', 'OFFLINE'),
    ('chi', '123456', 'Chi Do', 'OFFLINE'),
    ('duy', '123456', 'Duy Hoang', 'OFFLINE'),
    ('ha', '123456', 'Ha Vu', 'OFFLINE')
ON CONFLICT (username) DO NOTHING;

INSERT INTO rooms (name, type, owner_username)
VALUES
    ('DevTeam', 'room', 'admin'),
    ('General', 'room', 'admin'),
    ('Design', 'room', 'linh'),
    ('Marketing', 'room', 'minh'),
    ('QA', 'room', 'an'),
    ('Backend', 'room', 'bao'),
    ('Frontend', 'room', 'chi'),
    ('Mobile', 'room', 'duy'),
    ('Support', 'room', 'ha'),
    ('Product', 'room', 'user1')
ON CONFLICT (name) DO NOTHING;

INSERT INTO room_members (room_name, username, role)
VALUES
    ('DevTeam', 'admin', 'OWNER'),
    ('General', 'admin', 'OWNER'),
    ('Design', 'linh', 'OWNER'),
    ('Marketing', 'minh', 'OWNER'),
    ('QA', 'an', 'OWNER'),
    ('Backend', 'bao', 'OWNER'),
    ('Frontend', 'chi', 'OWNER'),
    ('Mobile', 'duy', 'OWNER'),
    ('Support', 'ha', 'OWNER'),
    ('Product', 'user1', 'OWNER')
ON CONFLICT (room_name, username) DO NOTHING;

INSERT INTO chat_themes (user1, user2, theme_id)
VALUES
    ('admin', 'user1', 'OCEAN_BLUE'),
    ('admin', 'user2', 'DARK_MODE'),
    ('user1', 'user2', 'SUNSET'),
    ('linh', 'minh', 'FOREST'),
    ('an', 'bao', 'LAVENDER'),
    ('chi', 'duy', 'ROSE'),
    ('ha', 'admin', 'MIDNIGHT'),
    ('linh', 'user1', 'MINT'),
    ('minh', 'user2', 'SKY'),
    ('bao', 'chi', 'CLASSIC')
ON CONFLICT (user1, user2) DO NOTHING;

INSERT INTO group_themes (group_name, theme_id, last_changed_by)
VALUES
    ('DevTeam', 'DARK_MODE', 'admin'),
    ('General', 'OCEAN_BLUE', 'admin'),
    ('Design', 'LAVENDER', 'linh'),
    ('Marketing', 'SUNSET', 'minh'),
    ('QA', 'MINT', 'an'),
    ('Backend', 'MIDNIGHT', 'bao'),
    ('Frontend', 'ROSE', 'chi'),
    ('Mobile', 'SKY', 'duy'),
    ('Support', 'FOREST', 'ha'),
    ('Product', 'CLASSIC', 'user1')
ON CONFLICT (group_name) DO NOTHING;

INSERT INTO pending_conversations (from_username, to_username, status)
VALUES
    ('admin', 'user1', 'ACCEPTED'),
    ('admin', 'user2', 'ACCEPTED'),
    ('user1', 'user2', 'PENDING'),
    ('linh', 'minh', 'ACCEPTED'),
    ('an', 'bao', 'ACCEPTED'),
    ('chi', 'duy', 'PENDING'),
    ('ha', 'admin', 'ACCEPTED'),
    ('linh', 'user1', 'PENDING'),
    ('minh', 'user2', 'ACCEPTED'),
    ('bao', 'chi', 'PENDING')
ON CONFLICT (from_username, to_username) DO NOTHING;

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
        ('people', 'user1', 'general', 'latest', NULL::timestamp, NULL::timestamp, 100, 12, 'seed-msg-001', 'Admin và user1 trao đổi về kế hoạch demo.', 'admin', 'seed'),
        ('people', 'user2', 'tasks', 'latest', NULL::timestamp, NULL::timestamp, 100, 8, 'seed-msg-002', 'Cần chuẩn bị tài liệu và kiểm tra lại luồng đăng nhập.', 'admin', 'seed'),
        ('people', 'user2', 'general', 'today', CURRENT_DATE::timestamp, (CURRENT_DATE + INTERVAL '1 day')::timestamp, 100, 6, 'seed-msg-003', 'User1 và user2 trao đổi nhanh về giao diện chat.', 'user1', 'seed'),
        ('people', 'minh', 'decisions', 'latest', NULL::timestamp, NULL::timestamp, 100, 10, 'seed-msg-004', 'Linh và Minh thống nhất dùng cloud database.', 'linh', 'seed'),
        ('people', 'bao', 'general', 'latest', NULL::timestamp, NULL::timestamp, 100, 15, 'seed-msg-005', 'An và Bao trao đổi checklist kiểm thử.', 'an', 'seed'),
        ('room', 'DevTeam', 'general', 'latest', NULL::timestamp, NULL::timestamp, 100, 30, 'seed-msg-006', 'Nhóm DevTeam cập nhật tiến độ backend và websocket.', 'admin', 'seed'),
        ('room', 'General', 'tasks', 'today', CURRENT_DATE::timestamp, (CURRENT_DATE + INTERVAL '1 day')::timestamp, 100, 18, 'seed-msg-007', 'Các thành viên cần cập nhật profile và kiểm tra trạng thái online.', 'admin', 'seed'),
        ('room', 'Design', 'general', 'latest', NULL::timestamp, NULL::timestamp, 100, 9, 'seed-msg-008', 'Nhóm Design trao đổi màu theme và avatar.', 'linh', 'seed'),
        ('room', 'Backend', 'decisions', 'latest', NULL::timestamp, NULL::timestamp, 100, 14, 'seed-msg-009', 'Nhóm Backend chốt lưu message ở MongoDB.', 'bao', 'seed'),
        ('room', 'Product', 'general', 'latest', NULL::timestamp, NULL::timestamp, 100, 11, 'seed-msg-010', 'Nhóm Product tổng hợp feedback cho bản thử nghiệm.', 'user1', 'seed')
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
