package com.dwcode.okxbot.horizon.job;

import com.dwcode.okxbot.horizon.config.HorizonProperties;
import com.dwcode.okxbot.horizon.service.HorizonCliRunner;
import com.dwcode.okxbot.horizon.service.HorizonIngestService;
import com.dwcode.okxbot.horizon.service.HorizonRefreshService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class HorizonRefreshJob {

    private final HorizonProperties properties;
    private final HorizonRefreshService refreshService;
    private final HorizonIngestService ingestService;

    @EventListener(ApplicationReadyEvent.class)
    public void generateTodayIfMissing() {
        if (!properties.isRefreshEnabled()) {
            return;
        }
        boolean haveShanghai = ingestService.hasToday("zh");
        Thread t = new Thread(() -> {
            try {
                if (!haveShanghai) {
                    log.info("horizon no Beijing-today digest in DB, startup refresh/import");
                    try {
                        refreshService.refresh(false, HorizonCliRunner.STARTUP_HOURS);
                        return;
                    } catch (Exception e) {
                        log.warn("horizon startup refresh failed: {}", e.getMessage());
                    }
                } else {
                    log.info("horizon Beijing-today digest exists, import file and publish");
                }
                refreshService.importSummaries();
            } catch (Exception e) {
                log.warn("horizon startup import/publish failed: {}", e.getMessage());
            }
        }, "horizon-startup-refresh");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(cron = "0 5 * * * ?")
    public void tick() {
        if (!properties.isRefreshEnabled()) {
            return;
        }
        try {
            refreshService.refresh(false);
        } catch (Exception e) {
            log.warn("HorizonRefreshJob skipped/failed: {}", e.getMessage());
        }
    }

    /** 线上 Horizon 容器自己产稿；工具台定期把 summaries 收进库。 */
    @Scheduled(cron = "0 */10 * * * ?")
    public void importTick() {
        try {
            refreshService.importSummaries();
        } catch (Exception e) {
            log.warn("horizon import skipped: {}", e.getMessage());
        }
    }
}
