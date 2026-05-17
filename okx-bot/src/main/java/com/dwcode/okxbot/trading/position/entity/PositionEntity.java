package com.dwcode.okxbot.trading.position.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 持仓实体。
 */
@Data
@TableName("position")
public class PositionEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long strategyId;

    private String symbol;

    private BigDecimal quantity;

    private BigDecimal avgPrice;

    private BigDecimal currentPrice;

    private BigDecimal realizedPnl;

    private BigDecimal unrealizedPnl;

    /** 状态 OPEN/CLOSED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
