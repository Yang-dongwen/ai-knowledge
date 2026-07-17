package com.dwcode.okxbot.pay.channel;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.pay.config.PayProperties;
import com.dwcode.okxbot.pay.enums.PayChannel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PaymentChannelRegistry {

    private final Map<String, PaymentChannel> channels;
    private final PayProperties payProperties;

    public PaymentChannelRegistry(List<PaymentChannel> channelList, PayProperties payProperties) {
        this.channels = channelList.stream()
                .collect(Collectors.toMap(PaymentChannel::channelId, Function.identity(), (a, b) -> a));
        this.payProperties = payProperties;
    }

    public PaymentChannel require(String channelId) {
        String id = PayChannel.normalize(channelId);
        if (PayChannel.MOCK.equals(id) && !payProperties.isMockEnabled()) {
            throw new BusinessException(400, "Mock 支付未开启");
        }
        if (PayChannel.ALIPAY.equals(id) && !payProperties.getAlipay().isEnabled()) {
            throw new BusinessException(400, "支付宝通道未开启");
        }
        if (PayChannel.WECHAT.equals(id) && !payProperties.getWechat().isEnabled()) {
            throw new BusinessException(400, "微信支付通道未开启");
        }
        PaymentChannel ch = channels.get(id);
        if (ch == null) {
            throw new BusinessException(400, "支付渠道未实现: " + id);
        }
        return ch;
    }
}
