package com.dwcode.okxbot.job;

import com.dwcode.okxbot.market.service.MarketCandleService;
import com.dwcode.okxbot.strategy.entity.StrategyConfigEntity;
import com.dwcode.okxbot.strategy.service.StrategyConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 行情同步定时任务。
 *
 * 定时从 OKX 拉取启用策略所需的 K 线数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketSyncJob {

    private final MarketCandleService marketCandleService;
    private final StrategyConfigService strategyConfigService;

    /**
     * 每分钟同步一次 K 线。
     */
    //    @Scheduled(fixedDelay = 60000)
    public void syncCandles() {
        try {
            List<StrategyConfigEntity> strategies = strategyConfigService.getEnabledStrategies();
            if (strategies.isEmpty()) {
                return;
            }

            // 去重：同一交易对+周期只同步一次
            Set<String> synced = new HashSet<>();
            for (StrategyConfigEntity strategy : strategies) {
                String key = strategy.getSymbol() + "_" + strategy.getTimeframe();
                if (synced.contains(key)) {
                    continue;
                }
                synced.add(key);

                int limit = strategy.getSlowPeriod() + 10;
                marketCandleService.syncRecentCandles(strategy.getSymbol(), strategy.getTimeframe(), limit);
            }
        } catch (Exception e) {
            log.error("行情同步任务异常", e);
        }
    }
}
