-- PC 端 Google / GitHub OAuth 绑定；纯三方用户允许无密码
ALTER TABLE sys_user
    MODIFY COLUMN password_hash VARCHAR(100) NULL COMMENT 'BCrypt密码哈希，纯第三方登录可空';

CREATE TABLE IF NOT EXISTS user_oauth_binding (
    id BIGINT NOT NULL COMMENT '主键',
    user_id BIGINT NOT NULL COMMENT 'sys_user.id',
    provider VARCHAR(32) NOT NULL COMMENT 'GOOGLE/GITHUB',
    provider_user_id VARCHAR(128) NOT NULL COMMENT '平台用户唯一 ID',
    email VARCHAR(128) NULL COMMENT '平台返回邮箱',
    display_name VARCHAR(128) NULL COMMENT '平台显示名',
    avatar_url VARCHAR(512) NULL COMMENT '头像 URL',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_provider_uid (provider, provider_user_id),
    INDEX idx_user_oauth_user_id (user_id),
    INDEX idx_user_oauth_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='第三方 OAuth 账号绑定';
