package com.dwcode.okxbot.pay.channel.mock;

import com.dwcode.okxbot.pay.channel.ChannelTradeQueryResult;
import com.dwcode.okxbot.pay.channel.NotifyParseResult;
import com.dwcode.okxbot.pay.channel.PayCreateContext;
import com.dwcode.okxbot.pay.channel.PaymentChannel;
import com.dwcode.okxbot.pay.channel.PaymentCreateResult;
import com.dwcode.okxbot.pay.entity.PayOrderEntity;
import com.dwcode.okxbot.pay.enums.PayChannel;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * 开发 Mock：不走公网 notify，由 POST /api/pay/mock/confirm 履约。
 */
@Component
public class MockPaymentChannel implements PaymentChannel {

    @Override
    public String channelId() {
        return PayChannel.MOCK;
    }

    @Override
    public PaymentCreateResult createPayment(PayOrderEntity order, PayCreateContext ctx) {
        String mockUrl = "mock://pay/" + order.getOrderNo();
        return PaymentCreateResult.builder()
                .payMode("MOCK")
                .codeUrl(mockUrl)
                .payUrl(null)
                .prepayId("MOCK-" + order.getOrderNo())
                .channelExtraJson("{\"mock\":true}")
                .build();
    }

    @Override
    public NotifyParseResult parseAndVerifyNotify(HttpHeaders headers, String rawBody) {
        return NotifyParseResult.builder()
                .signatureValid(false)
                .paid(false)
                .rawTradeState("MOCK_NO_NOTIFY")
                .build();
    }

    @Override
    public ChannelTradeQueryResult queryPayment(PayOrderEntity order) {
        // Mock 不主动查单成功；需 mock/confirm
        return ChannelTradeQueryResult.builder()
                .paid(false)
                .rawTradeState("NOTPAY")
                .amountCents(order.getAmountCents() == null ? 0 : order.getAmountCents())
                .build();
    }
}
