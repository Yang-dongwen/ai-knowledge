-- AI 对话：按用户隔离会话空间
-- 已有库执行本脚本；全新安装以 db/schema.sql 为准

ALTER TABLE chat_conversation
    ADD COLUMN user_id BIGINT NULL COMMENT '所属用户ID' AFTER id;

-- 历史数据无主：可先挂到指定超管，或保留 NULL（上线后不可见、可手工清理）
-- UPDATE chat_conversation SET user_id = 1 WHERE user_id IS NULL;

ALTER TABLE chat_conversation
    ADD INDEX idx_chat_user_updated (user_id, updated_at);
