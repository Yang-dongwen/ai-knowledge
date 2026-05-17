package com.dwcode.okxbot.strategy.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 策略配置实体。
 */
@Data
@TableName("strategy_config")
public class StrategyConfigEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String strategyName;

    private String symbol;

    private String timeframe;

    private Integer fastPeriod;

    private Integer slowPeriod;

    /** 单次买入资金比例，如 0.05 表示 5% */
    private BigDecimal tradeAmountPct;

    /** 止损比例，如 0.02 表示 2% */
    private BigDecimal stopLossPct;

    /** 止盈比例，如 0.05 表示 5% */
    private BigDecimal takeProfitPct;

    /** 是否启用 1是 0否 */
    private Integer enabled;

    /** 运行模式 PAPER/PROD */
    private String runMode;

    /** 最近运行K线时间戳，防止同一根K线重复执行 */
    private Long lastRunCandleTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
