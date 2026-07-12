package com.dwcode.okxbot.job;

import com.dwcode.okxbot.strategy.service.StrategyRunService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 策略运行定时任务。
 *
 * 每分钟运行一次已启用策略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyRunJob {

    private final StrategyRunService strategyRunService;

    /**
     * 每分钟执行一次策略。
     */
    // @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void runStrategies() {
        try {
            strategyRunService.runEnabledStrategies();
        } catch (Exception e) {
            log.error("策略运行任务异常", e);
        }
    }
}
