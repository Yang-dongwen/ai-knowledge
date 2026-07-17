package com.dwcode.okxbot.pay.channel;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PayCreateContext {
    private String clientType;
    private String clientIp;
    private String notifyAbsoluteUrl;
    private String returnAbsoluteUrl;
}
