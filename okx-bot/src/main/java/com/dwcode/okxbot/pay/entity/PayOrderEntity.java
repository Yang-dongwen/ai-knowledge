package com.dwcode.okxbot.pay.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pay_order")
public class PayOrderEntity {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String orderNo;
    private Long userId;
    private Long planId;
    private String planCode;
    private String planName;
    private Integer durationDays;
    private String channel;
    private String clientType;
    private Integer amountCents;
    private String currency;
    private String status;
    /** 0 未履约 1 已履约 */
    private Integer fulfilled;
    private String tradeNo;
    private String prepayId;
    private String codeUrl;
    private String payUrl;
    private String channelExtraJson;
    private String clientIp;
    private LocalDateTime expireAt;
    private LocalDateTime paidAt;
    private LocalDateTime closedAt;
    private String closeReason;
    private String refundStatus;
    private Integer refundAmountCents;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
