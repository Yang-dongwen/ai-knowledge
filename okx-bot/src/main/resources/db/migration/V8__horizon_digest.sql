-- 平台级时讯（无 user_id，全站同一份）
CREATE TABLE horizon_digest (
    digest_date     CHAR(10)      NOT NULL COMMENT 'YYYY-MM-DD',
    lang            VARCHAR(8)    NOT NULL DEFAULT 'zh',
    title           VARCHAR(200)  NOT NULL,
    markdown        MEDIUMTEXT    NOT NULL,
    snippet         VARCHAR(255)  NULL,
    halo_post_name  VARCHAR(128)  NULL,
    halo_permalink  VARCHAR(512)  NULL,
    updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (digest_date)
) COMMENT='Horizon 公共日报，全站一份，不按用户隔离';
