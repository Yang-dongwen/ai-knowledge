package com.dwcode.okxbot.pay.channel;

import com.dwcode.okxbot.pay.entity.PayOrderEntity;
import org.springframework.http.HttpHeaders;

public interface PaymentChannel {

    String channelId();

    PaymentCreateResult createPayment(PayOrderEntity order, PayCreateContext ctx);

    /**
     * 验签（微信含解密）后解析；amountCents 必须为分；paid 仅成功态。
     */
    NotifyParseResult parseAndVerifyNotify(HttpHeaders headers, String rawBody);

    ChannelTradeQueryResult queryPayment(PayOrderEntity order);
}
