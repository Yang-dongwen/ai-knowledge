package com.dwcode.okxbot.backtest.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回测交易明细实体。
 *
 * 一条记录表示一个完整的买卖回合（现货：买入开仓 -> 卖出平仓）。
 * 盈亏已扣除买入和卖出两端的手续费与滑点成本。
 */
@Data
@TableName("backtest_trade")
public class BacktestTradeEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long backtestTaskId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long strategyId;

    private String symbol;

    /** 方向，现货回合统一为 BUY（先买后卖） */
    private String side;

    /** 入场K线时间戳(毫秒) */
    private Long entryTime;

    /** 出场K线时间戳(毫秒) */
    private Long exitTime;

    /** 入场成交价(含滑点) */
    private BigDecimal entryPrice;

    /** 出场成交价(含滑点) */
    private BigDecimal exitPrice;

    /** 成交数量 */
    private BigDecimal quantity;

    /** 本回合手续费(买入+卖出) */
    private BigDecimal fee;

    /** 本回合滑点成本 */
    private BigDecimal slippageCost;

    /** 盈亏(已扣手续费和滑点) */
    private BigDecimal pnl;

    /** 盈亏比例 */
    private BigDecimal pnlPct;

    /** 持有K线数量 */
    private Integer holdingBars;

    private String entryReason;

    private String exitReason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
