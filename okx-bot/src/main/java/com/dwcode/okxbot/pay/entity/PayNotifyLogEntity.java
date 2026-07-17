package com.dwcode.okxbot.pay.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pay_notify_log")
public class PayNotifyLogEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String orderNo;
    private String channel;
    private String notifyId;
    private String bodyRaw;
    private String headersJson;
    private Integer verifyOk;
    private String processResult;
    private String errorMessage;
    private LocalDateTime createdAt;
}
