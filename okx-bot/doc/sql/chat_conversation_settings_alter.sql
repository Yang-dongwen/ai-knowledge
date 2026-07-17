-- AI 对话：会话级生成参数 + 自定义 system prompt
-- 已有库执行本脚本；全新安装以 db/schema.sql 为准

ALTER TABLE chat_conversation
    ADD COLUMN temperature DOUBLE NULL COMMENT '会话温度 0~2，空则默认 0.7' AFTER model,
    ADD COLUMN max_tokens INT NULL COMMENT '会话 max_tokens，空则默认 2000' AFTER temperature,
    ADD COLUMN system_prompt TEXT NULL COMMENT '会话自定义系统提示，空则用全局默认' AFTER max_tokens;
