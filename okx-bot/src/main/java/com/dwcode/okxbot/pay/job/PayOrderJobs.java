package com.dwcode.okxbot.pay.job;

import com.dwcode.okxbot.pay.service.PayOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 关单 / 查单补单 / 未履约补偿。V1 假定单实例。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayOrderJobs {

    private final PayOrderService payOrderService;

    @Scheduled(cron = "${pay.close-cron:0 */1 * * * ?}")
    public void closeExpired() {
        try {
            payOrderService.closeExpiredOrders();
        } catch (Exception e) {
            log.error("closeExpiredOrders failed: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "${pay.reconcile-cron:0 */1 * * * ?}")
    public void reconcile() {
        try {
            payOrderService.reconcilePayingOrders();
        } catch (Exception e) {
            log.error("reconcilePayingOrders failed: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "${pay.fulfill-pending-cron:0 */1 * * * ?}")
    public void fulfillPending() {
        try {
            payOrderService.fulfillPending();
        } catch (Exception e) {
            log.error("fulfillPending failed: {}", e.getMessage(), e);
        }
    }
}
