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
    }
}
