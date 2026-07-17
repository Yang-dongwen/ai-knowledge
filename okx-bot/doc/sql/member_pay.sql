-- 会员充值 / 支付订单（PR1）
-- 已有库升级：执行本脚本；列/表已存在时请按需跳过报错
USE okx_bot;

-- sys_user 增加会员到期（列已存在会报错，可忽略）
ALTER TABLE sys_user
    ADD COLUMN member_expire_at DATETIME(3) NULL COMMENT '会员到期时间，过期后仍保留展示' AFTER role;

-- 索引已存在会报错，可忽略
CREATE INDEX idx_sys_user_member_expire ON sys_user (member_expire_at);

CREATE TABLE IF NOT EXISTS member_plan (
    id                    BIGINT         NOT NULL COMMENT '主键 snowflake',
    code                  VARCHAR(64)    NOT NULL COMMENT 'month/quarter/year',
    name                  VARCHAR(128)   NOT NULL,
    description           VARCHAR(512)            ,
    duration_days         INT            NOT NULL COMMENT '应用层校验 >0',
    price_cents           INT            NOT NULL COMMENT '分；应用层校验 >0',
    original_price_cents  INT                     ,
    currency              VARCHAR(8)     NOT NULL DEFAULT 'CNY',
    status                TINYINT        NOT NULL DEFAULT 1 COMMENT '1上架 0下架',
    sort_order            INT            NOT NULL DEFAULT 0,
    created_at            DATETIME(3)    NOT NULL,
    updated_at            DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_plan_code (code),
    INDEX idx_member_plan_status_sort (status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员套餐';

CREATE TABLE IF NOT EXISTS pay_order (
    id                    BIGINT         NOT NULL,
    order_no              VARCHAR(32)    NOT NULL COMMENT 'out_trade_no',
    user_id               BIGINT         NOT NULL,
    plan_id               BIGINT         NOT NULL,
    plan_code             VARCHAR(64)    NOT NULL,
    plan_name             VARCHAR(128)   NOT NULL,
    duration_days         INT            NOT NULL,
    channel               VARCHAR(16)    NOT NULL COMMENT 'alipay/wechat/mock',
    client_type           VARCHAR(8)     NOT NULL DEFAULT 'PC',
    amount_cents          INT            NOT NULL,
    currency              VARCHAR(8)     NOT NULL DEFAULT 'CNY',
    status                VARCHAR(16)    NOT NULL DEFAULT 'CREATED',
    fulfilled             TINYINT        NOT NULL DEFAULT 0,
    trade_no              VARCHAR(64)             ,
    prepay_id             VARCHAR(128)            ,
    code_url              VARCHAR(512)            ,
    pay_url               VARCHAR(1024)           ,
    channel_extra_json    TEXT                    ,
    client_ip             VARCHAR(64)             ,
    expire_at             DATETIME(3)    NOT NULL,
    paid_at               DATETIME(3)             ,
    closed_at             DATETIME(3)             ,
    close_reason          VARCHAR(64)             ,
    refund_status         VARCHAR(16)             COMMENT '标记用 NONE/SUCCESS 等',
    refund_amount_cents   INT                     ,
    version               INT            NOT NULL DEFAULT 0,
    created_at            DATETIME(3)    NOT NULL,
    updated_at            DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pay_order_no (order_no),
    INDEX idx_pay_user_created (user_id, created_at),
    INDEX idx_pay_status_expire (status, expire_at),
    INDEX idx_pay_status_fulfilled (status, fulfilled, updated_at),
    INDEX idx_pay_trade_no (trade_no),
    INDEX idx_pay_channel_status (channel, status),
    UNIQUE KEY uk_pay_channel_trade (channel, trade_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付订单';

CREATE TABLE IF NOT EXISTS pay_notify_log (
    id                    BIGINT         NOT NULL,
    order_no              VARCHAR(32)             ,
    channel               VARCHAR(16)    NOT NULL,
    notify_id             VARCHAR(128)            ,
    body_raw              MEDIUMTEXT              ,
    headers_json          TEXT                    ,
    verify_ok             TINYINT        NOT NULL DEFAULT 0,
    process_result        VARCHAR(32)    NOT NULL,
    error_message         VARCHAR(512)            ,
    created_at            DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_notify_order (order_no),
    INDEX idx_notify_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付异步通知日志';

-- 种子套餐推荐由 MemberPlanInitializer 用雪花 ID 写入；以下固定 ID 仅空库演示可选
INSERT INTO member_plan (id, code, name, description, duration_days, price_cents, original_price_cents, currency, status, sort_order, created_at, updated_at)
VALUES
    (3010000000000000001, 'month',   '月卡', '30天会员',  30,  2900,  3900, 'CNY', 1, 10, NOW(3), NOW(3)),
    (3010000000000000002, 'quarter', '季卡', '90天会员',  90,  7900,  9900, 'CNY', 1, 20, NOW(3), NOW(3)),
    (3010000000000000003, 'year',    '年卡', '365天会员', 365, 19900, 29900, 'CNY', 1, 30, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE
    price_cents = VALUES(price_cents),
    duration_days = VALUES(duration_days),
    updated_at = VALUES(updated_at);
