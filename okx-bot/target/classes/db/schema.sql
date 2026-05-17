-- OKX 自动交易助手 第一版数据库建表脚本
-- 数据库：okx_bot

CREATE DATABASE IF NOT EXISTS okx_bot DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE okx_bot;

-- 1. OKX 配置表
CREATE TABLE IF NOT EXISTS okx_config (
    id BIGINT NOT NULL COMMENT '主键',
    api_key_masked VARCHAR(255) COMMENT '脱敏后的API Key',
    api_key_encrypted TEXT COMMENT '加密后的API Key',
    secret_key_encrypted TEXT COMMENT '加密后的Secret Key',
    passphrase_encrypted TEXT COMMENT '加密后的Passphrase',
    simulated TINYINT NOT NULL DEFAULT 1 COMMENT '是否模拟盘 1是 0否',
    status VARCHAR(32) NOT NULL DEFAULT 'ENABLED' COMMENT '状态 ENABLED/DISABLED/ERROR',
    last_check_at DATETIME(3) COMMENT '最近检测时间',
    last_error TEXT COMMENT '最近错误信息',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OKX配置表';

-- 2. 策略配置表
CREATE TABLE IF NOT EXISTS strategy_config (
    id BIGINT NOT NULL COMMENT '主键',
    strategy_name VARCHAR(128) NOT NULL COMMENT '策略名称',
    symbol VARCHAR(64) NOT NULL COMMENT '交易对',
    timeframe VARCHAR(16) NOT NULL COMMENT 'K线周期',
    fast_period INT NOT NULL COMMENT '快线周期',
    slow_period INT NOT NULL COMMENT '慢线周期',
    trade_amount_pct DECIMAL(18,10) NOT NULL COMMENT '单次买入资金比例',
    stop_loss_pct DECIMAL(18,10) NOT NULL COMMENT '止损比例',
    take_profit_pct DECIMAL(18,10) NOT NULL COMMENT '止盈比例',
    enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用 1是 0否',
    run_mode VARCHAR(32) NOT NULL DEFAULT 'PAPER' COMMENT '运行模式 PAPER/PROD',
    last_run_candle_time BIGINT COMMENT '最近运行K线时间戳',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_symbol (symbol),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略配置表';

-- 3. K线行情表
CREATE TABLE IF NOT EXISTS market_candle (
    id BIGINT NOT NULL COMMENT '主键',
    symbol VARCHAR(64) NOT NULL COMMENT '交易对',
    timeframe VARCHAR(16) NOT NULL COMMENT 'K线周期',
    candle_time BIGINT NOT NULL COMMENT 'K线开始时间戳(毫秒)',
    open_price DECIMAL(36,18) NOT NULL COMMENT '开盘价',
    high_price DECIMAL(36,18) NOT NULL COMMENT '最高价',
    low_price DECIMAL(36,18) NOT NULL COMMENT '最低价',
    close_price DECIMAL(36,18) NOT NULL COMMENT '收盘价',
    volume DECIMAL(36,18) NOT NULL COMMENT '成交量',
    confirmed TINYINT NOT NULL DEFAULT 0 COMMENT '是否已完成 1是 0否',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_candle (symbol, timeframe, candle_time),
    INDEX idx_symbol_timeframe (symbol, timeframe, candle_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='K线行情表';

-- 4. 交易订单表
CREATE TABLE IF NOT EXISTS trade_order (
    id BIGINT NOT NULL COMMENT '主键',
    strategy_id BIGINT NOT NULL COMMENT '策略ID',
    symbol VARCHAR(64) NOT NULL COMMENT '交易对',
    side VARCHAR(16) NOT NULL COMMENT '方向 BUY/SELL',
    order_type VARCHAR(32) NOT NULL DEFAULT 'MARKET' COMMENT '订单类型 MARKET/LIMIT',
    price DECIMAL(36,18) COMMENT '价格',
    quantity DECIMAL(36,18) COMMENT '数量',
    notional DECIMAL(36,18) COMMENT '金额',
    client_order_id VARCHAR(64) NOT NULL COMMENT '客户端订单ID',
    okx_order_id VARCHAR(128) COMMENT 'OKX订单ID',
    status VARCHAR(32) NOT NULL DEFAULT 'CREATED' COMMENT '订单状态',
    raw_request JSON COMMENT '原始请求',
    raw_response JSON COMMENT '原始响应',
    error_message TEXT COMMENT '错误信息',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_client_order_id (client_order_id),
    INDEX idx_strategy_id (strategy_id),
    INDEX idx_symbol (symbol),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易订单表';

-- 5. 持仓表
CREATE TABLE IF NOT EXISTS position (
    id BIGINT NOT NULL COMMENT '主键',
    strategy_id BIGINT NOT NULL COMMENT '策略ID',
    symbol VARCHAR(64) NOT NULL COMMENT '交易对',
    quantity DECIMAL(36,18) NOT NULL DEFAULT 0 COMMENT '持仓数量',
    avg_price DECIMAL(36,18) NOT NULL DEFAULT 0 COMMENT '平均买入价',
    current_price DECIMAL(36,18) NOT NULL DEFAULT 0 COMMENT '当前价格',
    realized_pnl DECIMAL(36,18) NOT NULL DEFAULT 0 COMMENT '已实现盈亏',
    unrealized_pnl DECIMAL(36,18) NOT NULL DEFAULT 0 COMMENT '未实现盈亏',
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN' COMMENT '状态 OPEN/CLOSED',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_strategy_symbol (strategy_id, symbol),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓表';

-- 6. 策略运行日志表
CREATE TABLE IF NOT EXISTS strategy_run_log (
    id BIGINT NOT NULL COMMENT '主键',
    strategy_id BIGINT NOT NULL COMMENT '策略ID',
    symbol VARCHAR(64) NOT NULL COMMENT '交易对',
    timeframe VARCHAR(16) NOT NULL COMMENT 'K线周期',
    candle_time BIGINT NOT NULL COMMENT 'K线时间戳',
    close_price DECIMAL(36,18) COMMENT '收盘价',
    fast_ma DECIMAL(36,18) COMMENT '快线值',
    slow_ma DECIMAL(36,18) COMMENT '慢线值',
    trade_signal VARCHAR(32) COMMENT '信号 BUY/SELL/HOLD',
    action VARCHAR(64) COMMENT '执行动作',
    message VARCHAR(512) COMMENT '说明信息',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_strategy_candle (strategy_id, symbol, timeframe, candle_time),
    INDEX idx_strategy_id (strategy_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略运行日志表';

-- 7. 系统状态表
CREATE TABLE IF NOT EXISTS system_state (
    id BIGINT NOT NULL COMMENT '主键',
    state_key VARCHAR(64) NOT NULL COMMENT '状态键',
    state_value VARCHAR(255) NOT NULL COMMENT '状态值',
    description VARCHAR(255) COMMENT '描述',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_state_key (state_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统状态表';

-- 初始化系统状态
INSERT INTO system_state (id, state_key, state_value, description, created_at, updated_at)
VALUES (1, 'TRADING_STATUS', 'RUNNING', '交易系统运行状态', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE state_value = state_value;

-- 8. 成交记录表
CREATE TABLE IF NOT EXISTS trade_fill (
    id BIGINT NOT NULL COMMENT '主键',
    order_id BIGINT COMMENT '关联订单ID',
    strategy_id BIGINT COMMENT '策略ID',
    symbol VARCHAR(64) NOT NULL COMMENT '交易对',
    side VARCHAR(16) NOT NULL COMMENT '方向 BUY/SELL',
    price DECIMAL(36,18) NOT NULL COMMENT '成交价格',
    quantity DECIMAL(36,18) NOT NULL COMMENT '成交数量',
    notional DECIMAL(36,18) COMMENT '成交金额',
    fee DECIMAL(36,18) COMMENT '手续费',
    fee_currency VARCHAR(32) COMMENT '手续费币种',
    realized_pnl DECIMAL(36,18) COMMENT '已实现盈亏',
    okx_order_id VARCHAR(128) COMMENT 'OKX订单ID',
    okx_trade_id VARCHAR(128) COMMENT 'OKX成交ID',
    raw_data JSON COMMENT '原始数据',
    trade_time DATETIME(3) NOT NULL COMMENT '成交时间',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_order_id (order_id),
    INDEX idx_strategy_id (strategy_id),
    INDEX idx_symbol (symbol),
    INDEX idx_trade_time (trade_time),
    UNIQUE KEY uk_okx_trade_id (okx_trade_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成交记录表';
