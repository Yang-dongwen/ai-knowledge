-- 角色权限：为已有库增加 role 列（可重复执行：列已存在会报错可忽略）
USE okx_bot;

ALTER TABLE sys_user
    ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'USER'
        COMMENT 'USER普通/MEMBER会员/SUPER_ADMIN超管'
        AFTER nickname;

-- 可选索引
ALTER TABLE sys_user ADD INDEX idx_role (role);

-- 说明：
-- 1) 新注册用户默认 role=USER
-- 2) 会员 MEMBER 由后续充值流程升级（本次不实现）
-- 3) 超级管理员由应用启动时 SuperAdminInitializer 自动种子
--    默认账号见 application.yml auth.admin（email/password）
