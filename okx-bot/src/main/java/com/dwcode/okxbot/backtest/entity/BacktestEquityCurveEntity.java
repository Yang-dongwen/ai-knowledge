package com.dwcode.okxbot.backtest.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回测资金曲线实体。
 *
 * 每根参与回测的K线记录一个权益点，用于绘制资金曲线和回撤曲线。
 */
@Data
@TableName("backtest_equity_curve")
public class BacktestEquityCurveEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long backtestTaskId;

    /** K线时间戳(毫秒) */
    private Long candleTime;

    /** 当前权益(现金 + 持仓市值) */
    private BigDecimal equity;

    /** 现金 */
    private BigDecimal cash;

    /** 持仓市值 */
    private BigDecimal positionValue;

    /** 当前回撤(相对历史峰值) */
    private BigDecimal drawdown;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
