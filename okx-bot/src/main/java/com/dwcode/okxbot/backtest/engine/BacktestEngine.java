package com.dwcode.okxbot.backtest.engine;

import com.dwcode.okxbot.backtest.dto.BacktestRunResult;
import com.dwcode.okxbot.backtest.entity.BacktestEquityCurveEntity;
import com.dwcode.okxbot.backtest.entity.BacktestTradeEntity;
import com.dwcode.okxbot.common.enums.TradeSignalEnum;
import com.dwcode.okxbot.common.util.BigDecimalUtil;
import com.dwcode.okxbot.market.entity.MarketCandleEntity;
import com.dwcode.okxbot.strategy.dto.SignalResult;
import com.dwcode.okxbot.strategy.engine.MaCrossStrategyEngine;
import com.dwcode.okxbot.strategy.entity.StrategyConfigEntity;
import com.dwcode.okxbot.trading.position.entity.PositionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 回测引擎。
 *
 * 职责：
 * 在历史K线上逐根回放策略，模拟成交并统计绩效。
 *
 * 设计要点（保证回测可信）：
 * 1. 复用 {@link MaCrossStrategyEngine}，回测信号逻辑与实盘完全一致，避免“两套策略”。
 * 2. 只接收已完成K线，不使用未完成K线。
 * 3. 不使用未来数据：第 i 根K线的信号只依赖第 i 根及之前的K线，
 *    成交价取第 i 根收盘价，与实盘“K线收盘后按市价成交”一致。
 * 4. 强制计入手续费和滑点。
 * 5. 输出每笔交易明细和资金曲线，结果可复现（同输入同输出）。
 *
 * 约束（与第一版实盘一致）：
 * - 现货、单交易对、单向持仓（先买后卖）。
 * - 买入用现金的 tradeAmountPct 比例；卖出一次性清仓。
 *
 * 注意：本类不访问数据库，不调用 OKX。只输入数据，输出结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BacktestEngine {

    private static final long MILLIS_PER_YEAR = 365L * 24 * 60 * 60 * 1000;

    /** 收益率/比率类指标精度 */
    private static final int RATIO_SCALE = 10;

    private final MaCrossStrategyEngine maCrossStrategyEngine;

    /**
     * 执行回测。
     *
     * @param candles 已完成K线列表，按时间升序，且已按回测区间过滤
     * @param config  策略配置（快慢线、止盈止损等）
     * @param initialCapital 初始资金(USDT)
     * @param feeRate 手续费率，如 0.001
     * @param slippageRate 滑点率，如 0.0005
     * @return 回测结果（绩效指标 + 交易明细 + 资金曲线）
     */
    public BacktestRunResult run(List<MarketCandleEntity> candles,
                                 StrategyConfigEntity config,
                                 BigDecimal initialCapital,
                                 BigDecimal feeRate,
                                 BigDecimal slippageRate) {

        int slowPeriod = config.getSlowPeriod();
        // 需要 slowPeriod+1 根做热身才能计算当前与上一根均线
        int warmup = slowPeriod + 1;
        if (candles.size() <= warmup) {
            throw new IllegalArgumentException(
                    "K线数量不足以回测，至少需要" + (warmup + 1) + "根已完成K线，当前" + candles.size() + "根");
        }

        // 回测状态
        BigDecimal cash = initialCapital;
        BigDecimal holdingQty = BigDecimal.ZERO;   // 当前持仓数量
        BigDecimal entryPrice = BigDecimal.ZERO;   // 入场成交价(含滑点)，即持仓均价
        BigDecimal entryCashOut = BigDecimal.ZERO; // 入场总现金支出(含手续费)
        Long entryTime = null;
        String entryReason = null;
        int entryBarIndex = -1;

        BigDecimal totalFee = BigDecimal.ZERO;
        BigDecimal totalSlippage = BigDecimal.ZERO;

        List<BacktestTradeEntity> trades = new ArrayList<>();
        List<BacktestEquityCurveEntity> equityCurve = new ArrayList<>();
        List<BigDecimal> equitySeries = new ArrayList<>();

        BigDecimal peakEquity = initialCapital;
        BigDecimal maxDrawdown = BigDecimal.ZERO;

        int barCount = 0;

        // 从第一根有足够热身数据的K线开始逐根回放
        for (int i = warmup; i < candles.size(); i++) {
            barCount++;
            MarketCandleEntity bar = candles.get(i);
            BigDecimal close = bar.getClosePrice();

            // 构造当前持仓快照，供策略引擎判断止盈止损（与实盘传入持仓一致）
            PositionEntity position = buildPosition(config, holdingQty, entryPrice);

            // 取最近 warmup 根K线，降序传入引擎（引擎内部会反转为升序）
            List<MarketCandleEntity> window = buildDescendingWindow(candles, i, warmup);
            SignalResult signal = maCrossStrategyEngine.generateSignal(window, config, position);

            boolean hasPosition = BigDecimalUtil.isPositive(holdingQty);

            if (signal.getSignal() == TradeSignalEnum.BUY && !hasPosition) {
                // ---------- 买入开仓 ----------
                // 成交价 = 收盘价 *(1+滑点)，买入比收盘价略高
                BigDecimal execPrice = applySlippage(close, slippageRate, true);
                // 分配现金 = 可用现金 * 买入比例；该金额为本次买入的总现金支出(含手续费)
                BigDecimal allocate = cash.multiply(config.getTradeAmountPct())
                        .setScale(BigDecimalUtil.AMOUNT_SCALE, RoundingMode.HALF_UP);

                if (BigDecimalUtil.isPositive(allocate)) {
                    BigDecimal buyFee = allocate.multiply(feeRate)
                            .setScale(BigDecimalUtil.AMOUNT_SCALE, RoundingMode.HALF_UP);
                    BigDecimal notional = allocate.subtract(buyFee);
                    BigDecimal qty = notional.divide(execPrice, BigDecimalUtil.QUANTITY_SCALE, RoundingMode.HALF_DOWN);

                    if (BigDecimalUtil.isPositive(qty)) {
                        cash = cash.subtract(allocate);
                        holdingQty = qty;
                        entryPrice = execPrice;
                        entryCashOut = allocate;
                        entryTime = bar.getCandleTime();
                        entryReason = signal.getReason();
                        entryBarIndex = i;

                        totalFee = totalFee.add(buyFee);
                        totalSlippage = totalSlippage.add(slippageCost(close, execPrice, qty));
                    }
                }

            } else if (signal.getSignal() == TradeSignalEnum.SELL && hasPosition) {
                // ---------- 卖出平仓（一次性清仓）----------
                BigDecimal execPrice = applySlippage(close, slippageRate, false);
                BigDecimal grossProceeds = holdingQty.multiply(execPrice)
                        .setScale(BigDecimalUtil.AMOUNT_SCALE, RoundingMode.HALF_UP);
                BigDecimal sellFee = grossProceeds.multiply(feeRate)
                        .setScale(BigDecimalUtil.AMOUNT_SCALE, RoundingMode.HALF_UP);
                BigDecimal netProceeds = grossProceeds.subtract(sellFee);

                cash = cash.add(netProceeds);
                totalFee = totalFee.add(sellFee);
                totalSlippage = totalSlippage.add(slippageCost(close, execPrice, holdingQty));

                // 本回合盈亏 = 卖出净收入 - 买入总支出
                BigDecimal pnl = netProceeds.subtract(entryCashOut);
                BigDecimal pnlPct = BigDecimalUtil.isPositive(entryCashOut)
                        ? pnl.divide(entryCashOut, RATIO_SCALE, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;

                BigDecimal roundFee = entryCashOut.multiply(feeRate)
                        .setScale(BigDecimalUtil.AMOUNT_SCALE, RoundingMode.HALF_UP)
                        .add(sellFee);
                BigDecimal roundSlippage = slippageCost(candles.get(entryBarIndex).getClosePrice(), entryPrice, holdingQty)
                        .add(slippageCost(close, execPrice, holdingQty));

                trades.add(buildTrade(config, entryTime, bar.getCandleTime(), entryPrice, execPrice,
                        holdingQty, roundFee, roundSlippage, pnl, pnlPct,
                        i - entryBarIndex, entryReason, signal.getReason()));

                // 清空持仓
                holdingQty = BigDecimal.ZERO;
                entryPrice = BigDecimal.ZERO;
                entryCashOut = BigDecimal.ZERO;
                entryTime = null;
                entryReason = null;
                entryBarIndex = -1;
            }

            // ---------- 逐根记录权益与回撤 ----------
            BigDecimal positionValue = holdingQty.multiply(close)
                    .setScale(BigDecimalUtil.AMOUNT_SCALE, RoundingMode.HALF_UP);
            BigDecimal equity = cash.add(positionValue);
            equitySeries.add(equity);

            if (BigDecimalUtil.gt(equity, peakEquity)) {
                peakEquity = equity;
            }
            BigDecimal drawdown = BigDecimalUtil.isPositive(peakEquity)
                    ? peakEquity.subtract(equity).divide(peakEquity, RATIO_SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            if (BigDecimalUtil.gt(drawdown, maxDrawdown)) {
                maxDrawdown = drawdown;
            }

            equityCurve.add(buildEquityPoint(bar.getCandleTime(), equity, cash, positionValue, drawdown));
        }

        BigDecimal finalEquity = equitySeries.isEmpty()
                ? initialCapital
                : equitySeries.get(equitySeries.size() - 1);

        return assemble(candles, config, initialCapital, finalEquity, maxDrawdown,
                totalFee, totalSlippage, barCount, trades, equityCurve, equitySeries);
    }

    /**
     * 组装绩效指标结果。
     */
    private BacktestRunResult assemble(List<MarketCandleEntity> candles,
                                       StrategyConfigEntity config,
                                       BigDecimal initialCapital,
                                       BigDecimal finalEquity,
                                       BigDecimal maxDrawdown,
                                       BigDecimal totalFee,
                                       BigDecimal totalSlippage,
                                       int barCount,
                                       List<BacktestTradeEntity> trades,
                                       List<BacktestEquityCurveEntity> equityCurve,
                                       List<BigDecimal> equitySeries) {
        BacktestRunResult result = new BacktestRunResult();
        result.setFinalEquity(finalEquity);
        result.setBarCount(barCount);
        result.setTotalFee(totalFee);
        result.setTotalSlippageCost(totalSlippage);
        result.setMaxDrawdown(maxDrawdown);
        result.setTrades(trades);
        result.setEquityCurve(equityCurve);

        // 总收益率
        BigDecimal totalReturn = finalEquity.subtract(initialCapital)
                .divide(initialCapital, RATIO_SCALE, RoundingMode.HALF_UP);
        result.setTotalReturn(totalReturn);

        // 年化收益率：根据回测时间跨度折算
        result.setAnnualReturn(calcAnnualReturn(candles, config, initialCapital, finalEquity));

        // 交易统计
        result.setTradeCount(trades.size());
        int wins = 0;
        BigDecimal grossProfit = BigDecimal.ZERO;
        BigDecimal grossLoss = BigDecimal.ZERO;
        int consecutiveLosses = 0;
        int maxConsecutiveLosses = 0;
        for (BacktestTradeEntity t : trades) {
            if (BigDecimalUtil.isPositive(t.getPnl())) {
                wins++;
                grossProfit = grossProfit.add(t.getPnl());
                consecutiveLosses = 0;
            } else {
                grossLoss = grossLoss.add(t.getPnl().abs());
                consecutiveLosses++;
                maxConsecutiveLosses = Math.max(maxConsecutiveLosses, consecutiveLosses);
            }
        }
        result.setMaxConsecutiveLosses(maxConsecutiveLosses);
        result.setWinRate(trades.isEmpty()
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(trades.size()), RATIO_SCALE, RoundingMode.HALF_UP));
        result.setProfitFactor(BigDecimalUtil.isPositive(grossLoss)
                ? grossProfit.divide(grossLoss, RATIO_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);

        // 夏普比率（基于逐根权益收益序列）
        result.setSharpeRatio(calcSharpe(equitySeries, candles, config));
        return result;
    }

    /**
     * 计算年化收益率。
     *
     * 用复利方式按时间跨度折算：annual = (final/initial)^(1/years) - 1。
     * 统计类指标允许使用 double 计算（不涉及资金精度）。
     */
    private BigDecimal calcAnnualReturn(List<MarketCandleEntity> candles,
                                        StrategyConfigEntity config,
                                        BigDecimal initialCapital,
                                        BigDecimal finalEquity) {
        long spanMillis = candles.get(candles.size() - 1).getCandleTime() - candles.get(0).getCandleTime();
        // 补一个周期，避免单根或跨度过小导致年化失真
        spanMillis += timeframeMillis(config.getTimeframe());
        double years = spanMillis / (double) MILLIS_PER_YEAR;
        if (years <= 0 || !BigDecimalUtil.isPositive(initialCapital)) {
            return BigDecimal.ZERO;
        }
        double ratio = finalEquity.doubleValue() / initialCapital.doubleValue();
        if (ratio <= 0) {
            // 本金接近亏光，年化按 -100% 处理
            return BigDecimal.valueOf(-1).setScale(RATIO_SCALE, RoundingMode.HALF_UP);
        }
        double annual = Math.pow(ratio, 1.0 / years) - 1.0;
        return BigDecimal.valueOf(annual).setScale(RATIO_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算夏普比率（无风险利率按0处理）。
     *
     * 基于逐根权益的简单收益率序列，再按每年K线根数年化。
     * 统计类指标允许使用 double 计算（不涉及资金精度）。
     */
    private BigDecimal calcSharpe(List<BigDecimal> equitySeries,
                                  List<MarketCandleEntity> candles,
                                  StrategyConfigEntity config) {
        if (equitySeries.size() < 3) {
            return BigDecimal.ZERO;
        }
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < equitySeries.size(); i++) {
            double prev = equitySeries.get(i - 1).doubleValue();
            if (prev > 0) {
                returns.add(equitySeries.get(i).doubleValue() / prev - 1.0);
            }
        }
        if (returns.size() < 2) {
            return BigDecimal.ZERO;
        }
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = returns.stream().mapToDouble(r -> (r - mean) * (r - mean)).sum() / (returns.size() - 1);
        double std = Math.sqrt(variance);
        if (std == 0) {
            return BigDecimal.ZERO;
        }
        double barsPerYear = MILLIS_PER_YEAR / (double) timeframeMillis(config.getTimeframe());
        double sharpe = (mean / std) * Math.sqrt(barsPerYear);
        return BigDecimal.valueOf(sharpe).setScale(RATIO_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 成交价应用滑点。
     * 买入：收盘价上浮（买得更贵）；卖出：收盘价下浮（卖得更便宜）。
     */
    private BigDecimal applySlippage(BigDecimal close, BigDecimal slippageRate, boolean isBuy) {
        BigDecimal factor = isBuy ? BigDecimal.ONE.add(slippageRate) : BigDecimal.ONE.subtract(slippageRate);
        return close.multiply(factor).setScale(BigDecimalUtil.PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 滑点成本 = |成交价 - 收盘价| * 数量。
     */
    private BigDecimal slippageCost(BigDecimal close, BigDecimal execPrice, BigDecimal qty) {
        return execPrice.subtract(close).abs().multiply(qty)
                .setScale(BigDecimalUtil.AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 构造持仓快照供策略引擎判断止盈止损。
     */
    private PositionEntity buildPosition(StrategyConfigEntity config, BigDecimal qty, BigDecimal avgPrice) {
        if (!BigDecimalUtil.isPositive(qty)) {
            return null;
        }
        PositionEntity position = new PositionEntity();
        position.setStrategyId(config.getId());
        position.setSymbol(config.getSymbol());
        position.setQuantity(qty);
        position.setAvgPrice(avgPrice);
        position.setStatus("OPEN");
        return position;
    }

    /**
     * 取以索引 i 结尾、长度为 size 的K线窗口，降序排列（引擎要求）。
     */
    private List<MarketCandleEntity> buildDescendingWindow(List<MarketCandleEntity> candles, int i, int size) {
        List<MarketCandleEntity> window = new ArrayList<>(size);
        for (int j = i; j > i - size; j--) {
            window.add(candles.get(j));
        }
        return window;
    }

    private BacktestTradeEntity buildTrade(StrategyConfigEntity config, Long entryTime, Long exitTime,
                                           BigDecimal entryPrice, BigDecimal exitPrice, BigDecimal qty,
                                           BigDecimal fee, BigDecimal slippageCost, BigDecimal pnl,
                                           BigDecimal pnlPct, int holdingBars,
                                           String entryReason, String exitReason) {
        BacktestTradeEntity trade = new BacktestTradeEntity();
        trade.setStrategyId(config.getId());
        trade.setSymbol(config.getSymbol());
        trade.setSide("BUY");
        trade.setEntryTime(entryTime);
        trade.setExitTime(exitTime);
        trade.setEntryPrice(entryPrice);
        trade.setExitPrice(exitPrice);
        trade.setQuantity(qty);
        trade.setFee(fee);
        trade.setSlippageCost(slippageCost);
        trade.setPnl(pnl);
        trade.setPnlPct(pnlPct);
        trade.setHoldingBars(holdingBars);
        trade.setEntryReason(entryReason);
        trade.setExitReason(exitReason);
        return trade;
    }

    private BacktestEquityCurveEntity buildEquityPoint(Long candleTime, BigDecimal equity,
                                                       BigDecimal cash, BigDecimal positionValue,
                                                       BigDecimal drawdown) {
        BacktestEquityCurveEntity point = new BacktestEquityCurveEntity();
        point.setCandleTime(candleTime);
        point.setEquity(equity);
        point.setCash(cash);
        point.setPositionValue(positionValue);
        point.setDrawdown(drawdown);
        return point;
    }

    /**
     * K线周期对应的毫秒数。
     */
    private long timeframeMillis(String timeframe) {
        if (timeframe == null || timeframe.isEmpty()) {
            return 3_600_000L;
        }
        String tf = timeframe.trim();
        String unit = tf.substring(tf.length() - 1);
        long value;
        try {
            value = Long.parseLong(tf.substring(0, tf.length() - 1));
        } catch (NumberFormatException e) {
            return 3_600_000L;
        }
        switch (unit) {
            case "m":
                return value * 60_000L;
            case "H":
            case "h":
                return value * 3_600_000L;
            case "D":
            case "d":
                return value * 86_400_000L;
            case "W":
            case "w":
                return value * 7L * 86_400_000L;
            case "M":
                return value * 30L * 86_400_000L;
            default:
                return 3_600_000L;
        }
    }
}
