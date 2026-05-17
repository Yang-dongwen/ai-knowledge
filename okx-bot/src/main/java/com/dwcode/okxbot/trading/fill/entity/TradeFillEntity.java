package com.dwcode.okxbot.trading.fill.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 成交记录实体。
 * 一笔订单可能有多笔成交。
 */
@Data
@TableName("trade_fill")
public class TradeFillEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联订单ID */
    private Long orderId;

    /** 策略ID */
    private Long strategyId;

    private String symbol;

    /** 方向 BUY/SELL */
    private String side;

    /** 成交价格 */
    private BigDecimal price;

    /** 成交数量 */
    private BigDecimal quantity;

    /** 成交金额 */
    private BigDecimal notional;

    /** 手续费 */
    private BigDecimal fee;

    /** 手续费币种 */
    private String feeCurrency;

    /** 已实现盈亏（卖出时计算） */
    private BigDecimal realizedPnl;

    /** OKX 订单ID */
    private String okxOrderId;

    /** OKX 成交ID */
    private String okxTradeId;

    /** 原始数据 */
    private String rawData;

    /** 成交时间 */
    private LocalDateTime tradeTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
