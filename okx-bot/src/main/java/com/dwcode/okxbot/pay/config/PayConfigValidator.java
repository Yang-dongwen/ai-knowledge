package com.dwcode.okxbot.pay.config;

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
        boolean prod = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p));
        if (prod && payProperties.isMockEnabled()) {
            throw new IllegalStateException("生产环境禁止 pay.mock-enabled=true，请关闭 Mock 通道");
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
                log.warn("pay.alipay.enabled=true 但密钥未配齐（app-id/private-key/alipay-public-key），"
                        + "选择支付宝下单将失败；无资质请设 enabled=false");
            } else {
                log.info("支付宝通道已启用 appId={} serverUrl={}",
                        alipay.getAppId(), alipay.getServerUrl());
            }
        } else {
            log.info("支付宝通道关闭（pay.alipay.enabled=false），代码已接入可随时配置密钥开启");
        }
    }
}
