-- 微信小程序 openid 绑定（Phase M2）
ALTER TABLE sys_user
    ADD COLUMN wx_mini_openid VARCHAR(64) NULL COMMENT '微信小程序 openid' AFTER email_verified;

CREATE UNIQUE INDEX uk_sys_user_wx_mini_openid ON sys_user (wx_mini_openid);
