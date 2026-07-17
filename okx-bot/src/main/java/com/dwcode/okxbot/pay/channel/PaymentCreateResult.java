package com.dwcode.okxbot.pay.channel;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaymentCreateResult {
    /** NATIVE_QR / H5_URL / MOCK */
    private String payMode;
    private String codeUrl;
    private String payUrl;
    private String prepayId;
    private String channelExtraJson;
}
