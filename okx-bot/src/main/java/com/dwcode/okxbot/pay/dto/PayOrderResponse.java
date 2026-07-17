package com.dwcode.okxbot.pay.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class PayOrderResponse {
    private String orderNo;
    private String channel;
    private String status;
    private Integer amountCents;
    private String amountYuan;
    private String planId;
    private String planCode;
    private String planName;
    private Integer durationDays;
    private String payMode;
    private String qrCodeUrl;
    private String payUrl;
    private Integer fulfilled;
    private String tradeNo;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime paidAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    /** 幂等复用未终态订单 */
    private Boolean idempotentReuse;
}
