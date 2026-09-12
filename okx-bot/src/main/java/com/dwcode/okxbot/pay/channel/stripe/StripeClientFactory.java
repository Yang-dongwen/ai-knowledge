package com.dwcode.okxbot.pay.channel.stripe;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.pay.config.PayProperties;
import com.stripe.net.RequestOptions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Stripe 请求选项。enabled=false 或密钥未配时不发起调用。
 * <p>沙箱密钥：{@code pk_test_} / {@code sk_test_}（见 https://docs.stripe.com/keys）。
 */
@Component
@RequiredArgsConstructor
public class StripeClientFactory {

    private final PayProperties payProperties;

    public boolean isReady() {
        PayProperties.Stripe s = payProperties.getStripe();
        return s.isEnabled()
                && StringUtils.hasText(s.getSecretKey())
                && StringUtils.hasText(s.getPublishableKey());
    }

    public void requireReady() {
        PayProperties.Stripe s = payProperties.getStripe();
        if (!s.isEnabled()) {
            throw new BusinessException(400, "Stripe 通道未开启（pay.stripe.enabled=false）");
        }
        if (!StringUtils.hasText(s.getSecretKey()) || !StringUtils.hasText(s.getPublishableKey())) {
            throw new BusinessException(503,
                    "Stripe 密钥未配置完整（需 publishable-key / secret-key）。沙箱请填 pk_test_ 与 sk_test_");
        }
        validateKeyMode(s);
    }

    public RequestOptions requestOptions() {
        requireReady();
        return RequestOptions.builder()
                .setApiKey(payProperties.getStripe().getSecretKey().trim())
                .build();
    }

    public static void validateKeyMode(PayProperties.Stripe s) {
        String pk = trim(s.getPublishableKey());
        String sk = trim(s.getSecretKey());
        if (s.isSandbox()) {
            if (StringUtils.hasText(pk) && !pk.startsWith("pk_test_")) {
                throw new BusinessException(503, "Stripe 沙箱要求公钥以 pk_test_ 开头（当前非测试公钥）");
            }
            if (StringUtils.hasText(sk) && !isTestSecret(sk)) {
                throw new BusinessException(503, "Stripe 沙箱要求私钥以 sk_test_ 或 rk_test_ 开头");
            }
        } else {
            if (StringUtils.hasText(pk) && !pk.startsWith("pk_live_")) {
                throw new BusinessException(503, "Stripe 正式环境要求公钥以 pk_live_ 开头");
            }
            if (StringUtils.hasText(sk) && !isLiveSecret(sk)) {
                throw new BusinessException(503, "Stripe 正式环境要求私钥以 sk_live_ 或 rk_live_ 开头");
            }
        }
    }

    private static boolean isTestSecret(String sk) {
        return sk.startsWith("sk_test_") || sk.startsWith("rk_test_");
    }

    private static boolean isLiveSecret(String sk) {
        return sk.startsWith("sk_live_") || sk.startsWith("rk_live_");
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
