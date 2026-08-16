-- 工具台账号绑定自己的 Halo（PAT 密文；超管走 yml 平台站，不写此表）
CREATE TABLE user_halo_binding (
    user_id          BIGINT        NOT NULL COMMENT 'sys_user.id' PRIMARY KEY,
    base_url         VARCHAR(256)  NOT NULL COMMENT 'Halo API 根',
    public_base_url  VARCHAR(256)  NOT NULL COMMENT '对外站点',
    token_cipher     VARCHAR(2048) NOT NULL COMMENT 'PAT AES-GCM',
    halo_username    VARCHAR(128)  NULL COMMENT '校验时读到的 Halo 用户名',
    verified_at      DATETIME(3)   NULL,
    created_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户 Halo 绑定';
