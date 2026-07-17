package com.dwcode.okxbot.member.job;

import com.dwcode.okxbot.member.service.MemberStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 过期会员批量降级兜底（me() 惰性降级为主）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberExpireJob {

    private final MemberStatusService memberStatusService;

    @Scheduled(cron = "${pay.member-expire-cron:0 5 * * * ?}")
    public void demoteExpired() {
        try {
            memberStatusService.demoteAllExpired();
        } catch (Exception e) {
            log.error("MemberExpireJob failed: {}", e.getMessage(), e);
        }
    }
}
