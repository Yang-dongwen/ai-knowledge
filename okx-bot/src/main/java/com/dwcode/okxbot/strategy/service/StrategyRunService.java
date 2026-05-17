package com.dwcode.okxbot.strategy.service;

import com.dwcode.okxbot.common.enums.TradeSignalEnum;
import com.dwcode.okxbot.common.util.BigDecimalUtil;
import com.dwcode.okxbot.market.entity.MarketCandleEntity;
import com.dwcode.okxbot.market.service.MarketCandleService;
import com.dwcode.okxbot.strategy.dto.SignalResult;
import com.dwcode.okxbot.strategy.engine.MaCrossStrategyEngine;
import com.dwcode.okxbot.strategy.entity.StrategyConfigEntity;
import com.dwcode.okxbot.strategy.entity.StrategyRunLogEntity;
import com.dwcode.okxbot.strategy.mapper.StrategyRunLogMapper;
import com.dwcode.okxbot.system.service.SystemStateService;
import com.dwcode.okxbot.trading.order.service.TradeOrderService;
import com.dwcode.okxbot.trading.position.entity.PositionEntity;
import com.dwcode.okxbot.trading.position.service.PositionService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 策略运行服务。
 *
 * 职责：
 * 1. 查询已启用策略
 * 2. 获取最新K线
 * 3. 调用策略引擎生成交易信号
 * 4. 根据交易信号触发下单流程
 *
 * 注意：
 * 本类不直接调用OKX接口，下单必须通过 TradeOrderService 完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyRunService {

    private final StrategyConfigService strategyConfigService;
    private final MarketCandleService marketCandleService;
    private final MaCrossStrategyEngine maCrossStrategyEngine;
    private final TradeOrderService tradeOrderService;
    private final PositionService positionService;
    private final SystemStateService systemStateService;
    private final StrategyRunLogMapper strategyRunLogMapper;
    private final com.dwcode.okxbot.okx.service.OkxConfigService okxConfigService;

    /**
     * 运行所有已启用策略。
     */
    public void runEnabledStrategies() {
        if (systemStateService.isStopped()) {
            log.debug("系统已停止，跳过策略运行");
            return;
        }

        List<StrategyConfigEntity> strategies = strategyConfigService.getEnabledStrategies();
        for (StrategyConfigEntity strategy : strategies) {
            try {
                runSingleStrategy(strategy);
            } catch (Exception e) {
                log.error("策略运行异常: strategyId={}, name={}, error={}",
                        strategy.getId(), strategy.getStrategyName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 执行单个策略。
     *
     * 关键限制：
     * 1. 系统处于停止状态时不执行
     * 2. 同一根K线不重复执行
     * 3. 无交易信号时只记录日志，不下单
     * 4. 有交易信号时必须通过下单服务统一处理
     */
    private void runSingleStrategy(StrategyConfigEntity strategy) {
        String symbol = strategy.getSymbol();
        String timeframe = strategy.getTimeframe();
        int requiredCandles = strategy.getSlowPeriod() + 1;

        // 获取最近已完成K线
        List<MarketCandleEntity> candles = marketCandleService.getRecentConfirmedCandles(symbol, timeframe, requiredCandles);

        if (candles.isEmpty()) {
            log.debug("无可用K线: strategyId={}, symbol={}", strategy.getId(), symbol);
            return;
        }

        // 同一根K线只能执行一次交易判断
        Long latestCandleTime = candles.get(0).getCandleTime();
        if (latestCandleTime.equals(strategy.getLastRunCandleTime())) {
            return;
        }

        // 获取当前持仓
        PositionEntity position = positionService.getPosition(strategy.getId(), symbol);

        // 调用策略引擎生成信号
        SignalResult signalResult = maCrossStrategyEngine.generateSignal(candles, strategy, position);

        // 记录策略运行日志
        recordRunLog(strategy, signalResult);

        // 更新最近运行K线时间
        if (signalResult.getCandleTime() != null) {
            strategyConfigService.updateLastRunCandleTime(strategy.getId(), signalResult.getCandleTime());
        }

        // 根据信号执行交易
        if (signalResult.getSignal() == TradeSignalEnum.BUY) {
            executeBuy(strategy, position);
        } else if (signalResult.getSignal() == TradeSignalEnum.SELL) {
            executeSell(strategy, position, signalResult.getClosePrice());
        }
    }

    /**
     * 执行买入。
     */
    private void executeBuy(StrategyConfigEntity strategy, PositionEntity position) {
        // 已有持仓不重复买入
        if (position != null && BigDecimalUtil.isPositive(position.getQuantity())) {
            log.info("已有持仓，不重复买入: strategyId={}", strategy.getId());
            return;
        }

        try {
            // 查询 USDT 可用余额
            JsonNode balanceData = okxConfigService.queryBalance();
            BigDecimal availableUsdt = extractAvailableUsdt(balanceData);

            if (!BigDecimalUtil.isPositive(availableUsdt)) {
                log.warn("USDT余额不足: strategyId={}", strategy.getId());
                return;
            }

            // 按配置比例计算买入金额
            BigDecimal buyAmount = BigDecimalUtil.multiply(availableUsdt, strategy.getTradeAmountPct());

            // 最小下单金额检查（OKX 现货最小约 1 USDT）
            if (BigDecimalUtil.lt(buyAmount, BigDecimal.ONE)) {
                log.warn("买入金额过小: strategyId={}, amount={}", strategy.getId(), buyAmount);
                return;
            }

            tradeOrderService.submitMarketBuyOrder(strategy.getId(), strategy.getSymbol(), buyAmount);

            // 买入后更新持仓（简化处理：使用当前价格估算）
            // 实际应该在订单成交后通过订单同步更新持仓
            log.info("买入订单已提交: strategyId={}, amount={}", strategy.getId(), buyAmount);

        } catch (Exception e) {
            log.error("买入执行失败: strategyId={}, error={}", strategy.getId(), e.getMessage());
        }
    }

    /**
     * 执行卖出。
     */
    private void executeSell(StrategyConfigEntity strategy, PositionEntity position, BigDecimal currentPrice) {
        // 无持仓不允许卖出
        if (position == null || !BigDecimalUtil.isPositive(position.getQuantity())) {
            log.info("无持仓，不执行卖出: strategyId={}", strategy.getId());
            return;
        }

        try {
            BigDecimal sellQuantity = position.getQuantity();
            tradeOrderService.submitMarketSellOrder(strategy.getId(), strategy.getSymbol(), sellQuantity);

            // 卖出后更新持仓
            if (currentPrice != null) {
                positionService.updatePositionAfterSell(strategy.getId(), strategy.getSymbol(), sellQuantity, currentPrice);
            }

            log.info("卖出订单已提交: strategyId={}, quantity={}", strategy.getId(), sellQuantity);

        } catch (Exception e) {
            log.error("卖出执行失败: strategyId={}, error={}", strategy.getId(), e.getMessage());
        }
    }

    /**
     * 记录策略运行日志。
     */
    private void recordRunLog(StrategyConfigEntity strategy, SignalResult signalResult) {
        StrategyRunLogEntity logEntity = new StrategyRunLogEntity();
        logEntity.setStrategyId(strategy.getId());
        logEntity.setSymbol(strategy.getSymbol());
        logEntity.setTimeframe(strategy.getTimeframe());
        logEntity.setCandleTime(signalResult.getCandleTime());
        logEntity.setClosePrice(signalResult.getClosePrice());
        logEntity.setFastMa(signalResult.getFastMa());
        logEntity.setSlowMa(signalResult.getSlowMa());
        logEntity.setTradeSignal(signalResult.getSignal().name());
        logEntity.setAction(signalResult.getSignal() == TradeSignalEnum.HOLD ? "无操作" : "触发" + signalResult.getSignal().name());
        logEntity.setMessage(signalResult.getReason());
        logEntity.setCreatedAt(LocalDateTime.now());

        try {
            strategyRunLogMapper.insert(logEntity);
        } catch (Exception e) {
            // 唯一约束冲突说明已经记录过，忽略
            log.debug("策略日志已存在: strategyId={}, candleTime={}", strategy.getId(), signalResult.getCandleTime());
        }
    }

    /**
     * 从余额数据中提取 USDT 可用余额。
     */
    private BigDecimal extractAvailableUsdt(JsonNode balanceData) {
        if (balanceData == null || !balanceData.isArray() || balanceData.isEmpty()) {
            return BigDecimal.ZERO;
        }

        JsonNode details = balanceData.get(0).path("details");
        if (!details.isArray()) {
            return BigDecimal.ZERO;
        }

        for (JsonNode detail : details) {
            if ("USDT".equals(detail.path("ccy").asText())) {
                String availBal = detail.path("availBal").asText("0");
                return new BigDecimal(availBal);
            }
        }
        return BigDecimal.ZERO;
    }
}
