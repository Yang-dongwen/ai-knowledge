-- 用户认证相关表 + video_task.user_id
USE okx_bot;

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT NOT NULL COMMENT '主键',
    email VARCHAR(128) NOT NULL COMMENT '邮箱/用户名',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt密码哈希',
    nickname VARCHAR(64) COMMENT '昵称',
    role VARCHAR(32) NOT NULL DEFAULT 'USER' COMMENT 'USER普通/MEMBER会员/SUPER_ADMIN超管',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
    email_verified TINYINT NOT NULL DEFAULT 0 COMMENT '1已验证',
    last_login_at DATETIME(3) COMMENT '最后登录时间',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_email (email),
    INDEX idx_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

CREATE TABLE IF NOT EXISTS email_code (
    id BIGINT NOT NULL COMMENT '主键',
    email VARCHAR(128) NOT NULL COMMENT '邮箱',
    code VARCHAR(16) NOT NULL COMMENT '验证码',
    purpose VARCHAR(32) NOT NULL COMMENT 'REGISTER/RESET_PASSWORD',
    expires_at DATETIME(3) NOT NULL COMMENT '过期时间',
    used TINYINT NOT NULL DEFAULT 0 COMMENT '0未使用 1已使用',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_email_purpose (email, purpose),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邮箱验证码表';

-- 已有 video_task 表时执行（列已存在会报错可忽略）
ALTER TABLE video_task ADD COLUMN user_id BIGINT COMMENT '所属用户ID' AFTER id;
ALTER TABLE video_task ADD INDEX idx_user_id (user_id);

-- 已有 sys_user 但无 role 列时，执行 doc/auth_role_migration.sql
