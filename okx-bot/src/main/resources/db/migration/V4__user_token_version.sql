-- 改密 / 主动吊销会话：JWT claim tv 与此列不一致则拒绝
ALTER TABLE sys_user
    ADD COLUMN token_version INT NOT NULL DEFAULT 0 COMMENT '会话版本，改密后递增' AFTER password_hash;
