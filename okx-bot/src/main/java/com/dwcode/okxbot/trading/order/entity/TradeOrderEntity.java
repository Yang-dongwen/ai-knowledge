package com.dwcode.okxbot.trading.order.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易订单实体。
 */
@Data
@TableName("trade_order")
public class TradeOrderEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long strategyId;

    private String symbol;

    /** 方向 BUY/SELL */
    private String side;

    /** 订单类型 MARKET/LIMIT */
    private String orderType;

    private BigDecimal price;

    private BigDecimal quantity;

    /** 委托金额 */
    private BigDecimal notional;

    /** 客户端订单ID，唯一 */
    private String clientOrderId;

    /** OKX订单ID */
    private String okxOrderId;

    /** 订单状态 */
    private String status;

    /** 原始请求 */
    private String rawRequest;

    /** 原始响应 */
    private String rawResponse;

    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
