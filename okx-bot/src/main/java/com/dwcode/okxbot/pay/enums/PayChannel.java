package com.dwcode.okxbot.pay.enums;

import com.dwcode.okxbot.common.exception.BusinessException;

public final class PayChannel {
    public static final String MOCK = "mock";
    public static final String ALIPAY = "alipay";
    public static final String WECHAT = "wechat";

    private PayChannel() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(400, "支付渠道不能为空");
        }
        String c = raw.trim().toLowerCase();
        if (!MOCK.equals(c) && !ALIPAY.equals(c) && !WECHAT.equals(c)) {
            throw new BusinessException(400, "不支持的支付渠道: " + raw);
        }
        return c;
    }
}
