package com.dwcode.okxbot.controller;

import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.market.service.MarketCandleService;
import com.dwcode.okxbot.okx.service.OkxConfigService;
import com.dwcode.okxbot.strategy.entity.StrategyConfigEntity;
import com.dwcode.okxbot.strategy.entity.StrategyRunLogEntity;
import com.dwcode.okxbot.strategy.mapper.StrategyRunLogMapper;
import com.dwcode.okxbot.strategy.service.StrategyConfigService;
import com.dwcode.okxbot.system.service.SystemStateService;
import com.dwcode.okxbot.trading.fill.entity.TradeFillEntity;
import com.dwcode.okxbot.trading.fill.service.TradeFillService;
import com.dwcode.okxbot.trading.order.entity.TradeOrderEntity;
import com.dwcode.okxbot.trading.order.mapper.TradeOrderMapper;
import com.dwcode.okxbot.trading.position.entity.PositionEntity;
import com.dwcode.okxbot.trading.position.service.PositionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 仪表盘聚合接口。
 * 一次返回首页所需的全部数据，减少前端请求次数。
 */
@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final OkxConfigService okxConfigService;
    private final StrategyConfigService strategyConfigService;
    private final PositionService positionService;
    private final TradeFillService tradeFillService;
    private final SystemStateService systemStateService;
    private final TradeOrderMapper tradeOrderMapper;
    private final StrategyRunLogMapper strategyRunLogMapper;

    @GetMapping("/overview")
    public ApiResult<Map<String, Object>> getOverview() {
        Map<String, Object> overview = new HashMap<>();

        // 1. 系统状态
        overview.put("systemStatus", systemStateService.getSystemStatus());

        // 2. 账户资产
        Map<String, Object> account = new HashMap<>();
        try {
            JsonNode balanceData = okxConfigService.queryBalance();
            if (balanceData != null && balanceData.isArray() && !balanceData.isEmpty()) {
                JsonNode details = balanceData.get(0).path("details");
                if (details.isArray()) {
                    for (JsonNode detail : details) {
                        if ("USDT".equals(detail.path("ccy").asText())) {
                            account.put("availableBalance", detail.path("availBal").asText("0"));
                            account.put("totalEquity", detail.path("eq").asText(detail.path("availBal").asText("0")));
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            account.put("availableBalance", "0");
            account.put("totalEquity", "0");
        }
        overview.put("account", account);

        // 3. 当前持仓
        List<PositionEntity> positions = positionService.listPositions();
        BigDecimal positionMarketValue = BigDecimal.ZERO;
        BigDecimal totalUnrealizedPnl = BigDecimal.ZERO;
        BigDecimal totalRealizedPnl = BigDecimal.ZERO;
        for (PositionEntity p : positions) {
            BigDecimal mv = p.getQuantity().multiply(p.getCurrentPrice());
            positionMarketValue = positionMarketValue.add(mv);
            totalUnrealizedPnl = totalUnrealizedPnl.add(p.getUnrealizedPnl() != null ? p.getUnrealizedPnl() : BigDecimal.ZERO);
            totalRealizedPnl = totalRealizedPnl.add(p.getRealizedPnl() != null ? p.getRealizedPnl() : BigDecimal.ZERO);
        }
        Map<String, Object> positionSummary = new HashMap<>();
        positionSummary.put("count", positions.size());
        positionSummary.put("marketValue", positionMarketValue);
        positionSummary.put("unrealizedPnl", totalUnrealizedPnl);
        positionSummary.put("realizedPnl", totalRealizedPnl);
        positionSummary.put("list", positions);
        overview.put("positions", positionSummary);

        // 4. 策略
        List<StrategyConfigEntity> strategies = strategyConfigService.listStrategies();
        long enabledCount = strategies.stream().filter(s -> s.getEnabled() == 1).count();
        Map<String, Object> strategySummary = new HashMap<>();
        strategySummary.put("total", strategies.size());
        strategySummary.put("enabled", enabledCount);
        strategySummary.put("list", strategies);
        overview.put("strategies", strategySummary);

        // 5. 最近成交
        List<TradeFillEntity> recentTrades = tradeFillService.getRecentFills(5);
        overview.put("recentTrades", recentTrades);

        // 6. 最近订单
        List<TradeOrderEntity> recentOrders = tradeOrderMapper.selectList(
                new LambdaQueryWrapper<TradeOrderEntity>()
                        .orderByDesc(TradeOrderEntity::getCreatedAt)
                        .last("LIMIT 5")
        );
        overview.put("recentOrders", recentOrders);

        // 7. 最近运行日志
        List<StrategyRunLogEntity> recentLogs = strategyRunLogMapper.selectList(
                new LambdaQueryWrapper<StrategyRunLogEntity>()
                        .orderByDesc(StrategyRunLogEntity::getCreatedAt)
                        .last("LIMIT 5")
        );
        overview.put("recentLogs", recentLogs);

        // 8. 成交统计
        overview.put("tradeSummary", tradeFillService.getSummary());

        return ApiResult.ok(overview);
    }
}
