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

-- 9. AI 对话会话表
CREATE TABLE IF NOT EXISTS chat_conversation (
    id BIGINT NOT NULL COMMENT '主键',
    title VARCHAR(255) NOT NULL DEFAULT '新对话' COMMENT '会话标题',
    provider VARCHAR(64) COMMENT '供应商标识',
    model VARCHAR(128) COMMENT '模型ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI对话会话表';

-- 10. AI 对话消息表
CREATE TABLE IF NOT EXISTS chat_message (
    id BIGINT NOT NULL COMMENT '主键',
    conversation_id BIGINT NOT NULL COMMENT '会话ID',
    role VARCHAR(16) NOT NULL COMMENT '角色 user/assistant',
    content TEXT NOT NULL COMMENT '消息内容',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI对话消息表';

-- ============================================================
-- 回测模块表（可信回测引擎）
-- 设计原则：
--   1. 只使用已完成K线(confirmed=1)做回测
--   2. 强制计入手续费与滑点
--   3. 保存每笔交易明细与资金曲线，结果可复现
-- ============================================================

-- 11. 回测任务表
CREATE TABLE IF NOT EXISTS backtest_task (
    id BIGINT NOT NULL COMMENT '主键',
    strategy_id BIGINT NOT NULL COMMENT '策略ID',
    symbol VARCHAR(64) NOT NULL COMMENT '交易对',
    timeframe VARCHAR(16) NOT NULL COMMENT 'K线周期',
    start_time BIGINT COMMENT '回测开始K线时间戳(毫秒)，空表示从最早数据开始',
    end_time BIGINT COMMENT '回测结束K线时间戳(毫秒)，空表示到最新数据',
    initial_capital DECIMAL(36,18) NOT NULL COMMENT '初始资金(USDT)',
    fee_rate DECIMAL(18,10) NOT NULL COMMENT '手续费率',
    slippage_rate DECIMAL(18,10) NOT NULL COMMENT '滑点率',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态 PENDING/RUNNING/SUCCESS/FAILED',
    final_equity DECIMAL(36,18) COMMENT '期末权益',
    total_return DECIMAL(18,10) COMMENT '总收益率',
    annual_return DECIMAL(18,10) COMMENT '年化收益率',
    max_drawdown DECIMAL(18,10) COMMENT '最大回撤',
    sharpe_ratio DECIMAL(18,10) COMMENT '夏普比率',
    win_rate DECIMAL(18,10) COMMENT '胜率',
    profit_factor DECIMAL(18,10) COMMENT '盈亏比(Profit Factor)',
    trade_count INT COMMENT '交易次数(完整买卖回合)',
    max_consecutive_losses INT COMMENT '最大连续亏损次数',
    total_fee DECIMAL(36,18) COMMENT '手续费总额',
    total_slippage_cost DECIMAL(36,18) COMMENT '滑点成本总额',
    bar_count INT COMMENT '参与回测的K线数量',
    result_summary JSON COMMENT '结果摘要',
    error_message TEXT COMMENT '错误信息',
    started_at DATETIME(3) COMMENT '开始时间',
    finished_at DATETIME(3) COMMENT '完成时间',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_strategy_id (strategy_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测任务表';

-- 12. 回测交易明细表
CREATE TABLE IF NOT EXISTS backtest_trade (
    id BIGINT NOT NULL COMMENT '主键',
    backtest_task_id BIGINT NOT NULL COMMENT '回测任务ID',
    strategy_id BIGINT NOT NULL COMMENT '策略ID',
    symbol VARCHAR(64) NOT NULL COMMENT '交易对',
    side VARCHAR(16) NOT NULL COMMENT '方向 BUY(现货做多回合)',
    entry_time BIGINT NOT NULL COMMENT '入场K线时间戳(毫秒)',
    exit_time BIGINT NOT NULL COMMENT '出场K线时间戳(毫秒)',
    entry_price DECIMAL(36,18) NOT NULL COMMENT '入场成交价(含滑点)',
    exit_price DECIMAL(36,18) NOT NULL COMMENT '出场成交价(含滑点)',
    quantity DECIMAL(36,18) NOT NULL COMMENT '成交数量',
    fee DECIMAL(36,18) NOT NULL COMMENT '本回合手续费(买入+卖出)',
    slippage_cost DECIMAL(36,18) NOT NULL COMMENT '本回合滑点成本',
    pnl DECIMAL(36,18) NOT NULL COMMENT '盈亏(已扣手续费和滑点)',
    pnl_pct DECIMAL(18,10) NOT NULL COMMENT '盈亏比例',
    holding_bars INT NOT NULL COMMENT '持有K线数量',
    entry_reason VARCHAR(512) COMMENT '入场原因',
    exit_reason VARCHAR(512) COMMENT '出场原因',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_task_id (backtest_task_id),
    INDEX idx_strategy_id (strategy_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测交易明细表';

-- 13. 回测资金曲线表
CREATE TABLE IF NOT EXISTS backtest_equity_curve (
    id BIGINT NOT NULL COMMENT '主键',
    backtest_task_id BIGINT NOT NULL COMMENT '回测任务ID',
    candle_time BIGINT NOT NULL COMMENT 'K线时间戳(毫秒)',
    equity DECIMAL(36,18) NOT NULL COMMENT '当前权益(现金+持仓市值)',
    cash DECIMAL(36,18) NOT NULL COMMENT '现金',
    position_value DECIMAL(36,18) NOT NULL COMMENT '持仓市值',
    drawdown DECIMAL(18,10) NOT NULL COMMENT '当前回撤',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_task_candle (backtest_task_id, candle_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测资金曲线表';

-- ============================================================
-- 视频核心内容提取模块（VideoCoreExtractor）
-- 流程：下载(yt-dlp) → 音频提取(FFmpeg) → 转录(Whisper) → 总结(LLM)
-- ============================================================

-- 14. 视频处理任务表（v2 持久化：视频/转录/核心内容）
CREATE TABLE IF NOT EXISTS video_task (
    id BIGINT NOT NULL COMMENT '主键',
    source_url VARCHAR(1024) NOT NULL COMMENT '源视频URL',
    title VARCHAR(512) COMMENT '视频标题',
    platform VARCHAR(32) COMMENT '平台 douyin/bilibili/youtube/xiaohongshu/other',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态 PENDING/DOWNLOADING/TRANSCRIBING/SUMMARIZING/SUCCESS/FAILED',
    current_step VARCHAR(128) COMMENT '当前步骤说明',
    language VARCHAR(16) DEFAULT 'zh' COMMENT '语言',
    llm_provider VARCHAR(64) COMMENT 'LLM供应商标识',
    llm_model VARCHAR(128) COMMENT 'LLM模型ID',
    extract_mind_map TINYINT NOT NULL DEFAULT 1 COMMENT '是否提取思维导图 1是 0否',
    generate_repurpose_script TINYINT NOT NULL DEFAULT 1 COMMENT '是否生成repurpose脚本 1是 0否',
    duration_seconds DOUBLE COMMENT '视频时长(秒)',
    video_path VARCHAR(1024) COMMENT '本地视频路径',
    audio_path VARCHAR(1024) COMMENT '本地音频路径',
    transcription_path VARCHAR(1024) COMMENT '转录JSON文件路径',
    summary_path VARCHAR(1024) COMMENT '摘要JSON文件路径',
    transcription_json LONGTEXT COMMENT '转录结果JSON(带时间戳)',
    summary_json LONGTEXT COMMENT 'AI核心内容JSON',
    result_json LONGTEXT COMMENT '完整结构化结果JSON',
    error_message TEXT COMMENT '错误信息',
    download_duration_ms BIGINT COMMENT '下载步骤耗时(毫秒)',
    transcribe_duration_ms BIGINT COMMENT '转录步骤耗时(毫秒)',
    summarize_duration_ms BIGINT COMMENT '总结步骤耗时(毫秒)',
    total_duration_ms BIGINT COMMENT '全流程总耗时(毫秒)',
    started_at DATETIME(3) COMMENT '开始处理时间',
    finished_at DATETIME(3) COMMENT '完成时间',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_status (status),
    INDEX idx_platform (platform),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频处理任务表';

-- 15. 可配置 LLM 模型表（替代 yml 中 providers.*.models）
CREATE TABLE IF NOT EXISTS ai_model_config (
    id BIGINT NOT NULL COMMENT '主键',
    provider VARCHAR(64) NOT NULL COMMENT '供应商标识，对应 ai.providers 的 key',
    model_id VARCHAR(128) NOT NULL COMMENT 'API模型ID',
    model_name VARCHAR(128) NOT NULL COMMENT '展示名称',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用 1是 0否',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序，越小越靠前',
    remark VARCHAR(255) COMMENT '备注',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_provider_model (provider, model_id),
    INDEX idx_enabled_sort (enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM模型配置表';

