package com.dwcode.okxbot.backtest.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回测任务实体。
 *
 * 保存一次回测的输入参数与绩效结果摘要。
 * 每笔交易明细见 backtest_trade，资金曲线见 backtest_equity_curve。
 */
@Data
@TableName("backtest_task")
public class BacktestTaskEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long strategyId;

    private String symbol;

    private String timeframe;

    /** 回测开始K线时间戳(毫秒)，为空表示从最早数据开始 */
    private Long startTime;

    /** 回测结束K线时间戳(毫秒)，为空表示到最新数据 */
    private Long endTime;

    /** 初始资金(USDT) */
    private BigDecimal initialCapital;

    /** 手续费率，如 0.001 表示 0.1% */
    private BigDecimal feeRate;

    /** 滑点率，如 0.0005 表示 0.05% */
    private BigDecimal slippageRate;

    /** 状态 PENDING/RUNNING/SUCCESS/FAILED */
    private String status;

    /** 期末权益 */
    private BigDecimal finalEquity;

    /** 总收益率 */
    private BigDecimal totalReturn;

    /** 年化收益率 */
    private BigDecimal annualReturn;

    /** 最大回撤 */
    private BigDecimal maxDrawdown;

    /** 夏普比率 */
    private BigDecimal sharpeRatio;

    /** 胜率 */
    private BigDecimal winRate;

    /** 盈亏比(Profit Factor) */
    private BigDecimal profitFactor;

    /** 交易次数(完整买卖回合) */
    private Integer tradeCount;

    /** 最大连续亏损次数 */
    private Integer maxConsecutiveLosses;

    /** 手续费总额 */
    private BigDecimal totalFee;

    /** 滑点成本总额 */
    private BigDecimal totalSlippageCost;

    /** 参与回测的K线数量 */
    private Integer barCount;

    /** 结果摘要(JSON字符串) */
    private String resultSummary;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
