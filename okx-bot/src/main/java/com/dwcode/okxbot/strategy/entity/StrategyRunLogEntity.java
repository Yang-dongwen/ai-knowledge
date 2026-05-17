package com.dwcode.okxbot.strategy.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 策略运行日志实体。
 */
@Data
@TableName("strategy_run_log")
public class StrategyRunLogEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long strategyId;

    private String symbol;

    private String timeframe;

    /** K线时间戳 */
    private Long candleTime;

    private BigDecimal closePrice;

    private BigDecimal fastMa;

    private BigDecimal slowMa;

    /** 信号 BUY/SELL/HOLD */
    @TableField("trade_signal")
    private String tradeSignal;

    /** 执行动作 */
    private String action;

    /** 说明信息 */
    private String message;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
