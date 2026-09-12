package com.dwcode.okxbot.pay.config;

import com.dwcode.okxbot.pay.channel.stripe.StripeClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 生产环境禁止 mock-enabled=true。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayConfigValidator {

    private final PayProperties payProperties;
    private final Environment environment;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        boolean prodLike = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "prod".equalsIgnoreCase(p)
                        || "production".equalsIgnoreCase(p)
                        || "ec2".equalsIgnoreCase(p));
        if (prodLike && payProperties.isMockEnabled()) {
            throw new IllegalStateException(
                    "生产类环境（prod/production/ec2）禁止 pay.mock-enabled=true，请设置 PAY_MOCK_ENABLED=false");
        }
        if (payProperties.isMockEnabled()) {
            log.warn("pay.mock-enabled=true：可使用 POST /api/pay/mock/confirm 模拟支付（勿用于生产）");
        }
        PayProperties.Alipay alipay = payProperties.getAlipay();
        if (alipay.isEnabled()) {
            boolean keysOk = alipay.getAppId() != null && !alipay.getAppId().isBlank()
                    && alipay.getPrivateKey() != null && !alipay.getPrivateKey().isBlank()
                    && alipay.getAlipayPublicKey() != null && !alipay.getAlipayPublicKey().isBlank();
            if (!keysOk) {
                throw new IllegalStateException(
                        "pay.alipay.enabled=true 但密钥未配齐（app-id/private-key/alipay-public-key）");
            }
            log.info("支付宝通道已启用 appId={} serverUrl={}",
                    alipay.getAppId(), alipay.getServerUrl());
        } else {
            log.info("支付宝通道关闭（pay.alipay.enabled=false），代码已接入可随时配置密钥开启");
        }
        PayProperties.Stripe stripe = payProperties.getStripe();
        if (stripe.isEnabled()) {
            boolean keysOk = stripe.getPublishableKey() != null && !stripe.getPublishableKey().isBlank()
                    && stripe.getSecretKey() != null && !stripe.getSecretKey().isBlank();
            if (!keysOk) {
                throw new IllegalStateException(
                        "pay.stripe.enabled=true 但密钥未配齐（publishable-key/secret-key）");
            }
            try {
                StripeClientFactory.validateKeyMode(stripe);
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (stripe.getWebhookSecret() == null || stripe.getWebhookSecret().isBlank()) {
                log.warn("Stripe webhook-secret 未配置：异步通知将拒收，依赖查单补偿。沙箱可用 stripe listen --forward-to");
            }
            log.info("Stripe 通道已启用 sandbox={} publishableKeyPrefix={}",
                    stripe.isSandbox(),
                    stripe.getPublishableKey().trim().substring(0, Math.min(8, stripe.getPublishableKey().trim().length())));
        } else {
            log.info("Stripe 通道关闭（pay.stripe.enabled=false），沙箱可配 pk_test_/sk_test_ 后开启");
        }
    }
}
