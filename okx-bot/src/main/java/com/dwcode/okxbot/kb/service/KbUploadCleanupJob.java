package com.dwcode.okxbot.kb.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KbUploadCleanupJob {

    private final KbFileUploadService uploadService;

    @Scheduled(cron = "${kb.file.cleanup-cron:0 */15 * * * ?}")
    public void cleanupExpired() {
        try {
            int n = uploadService.cleanupExpired();
            if (n > 0) {
                log.info("kb upload expired cleaned count={}", n);
            }
        } catch (Exception e) {
            log.error("kb upload cleanup failed: {}", e.getMessage(), e);
        }
    }
}
