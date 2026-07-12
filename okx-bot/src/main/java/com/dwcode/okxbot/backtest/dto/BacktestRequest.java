package com.dwcode.okxbot.backtest.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建回测任务请求。
 *
 * 策略参数(快慢线、止盈止损等)从 strategy_config 读取，
 * 这里只提供回测区间与成交假设(资金、手续费、滑点)。
 */
@Data
public class BacktestRequest {

    @NotNull(message = "策略ID不能为空")
    private Long strategyId;

    /** 回测开始K线时间戳(毫秒)，为空表示从最早可用数据开始 */
    private Long startTime;

    /** 回测结束K线时间戳(毫秒)，为空表示到最新可用数据 */
    private Long endTime;

    @NotNull(message = "初始资金不能为空")
    @DecimalMin(value = "1", message = "初始资金至少为1")
    private BigDecimal initialCapital;

    /** 手续费率，如 0.001 表示 0.1%。OKX 现货吃单费率约 0.1% */
    @NotNull(message = "手续费率不能为空")
    @DecimalMin(value = "0", message = "手续费率不能为负")
    @DecimalMax(value = "0.1", message = "手续费率不合理")
    private BigDecimal feeRate;

    /** 滑点率，如 0.0005 表示 0.05% */
    @NotNull(message = "滑点率不能为空")
    @DecimalMin(value = "0", message = "滑点率不能为负")
    @DecimalMax(value = "0.1", message = "滑点率不合理")
    private BigDecimal slippageRate;
}
