package com.dwcode.okxbot.strategy.engine;

import com.dwcode.okxbot.common.enums.TradeSignalEnum;
import com.dwcode.okxbot.common.util.BigDecimalUtil;
import com.dwcode.okxbot.market.entity.MarketCandleEntity;
import com.dwcode.okxbot.strategy.dto.SignalResult;
import com.dwcode.okxbot.strategy.entity.StrategyConfigEntity;
import com.dwcode.okxbot.trading.position.entity.PositionEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 均线交叉策略引擎。
 *
 * 职责：
 * 1. 计算快线和慢线均值
 * 2. 判断均线交叉信号
 * 3. 判断止盈止损
 *
 * 注意：
 * 本类不访问数据库，不调用 OKX，不修改订单和持仓。
 * 只输入数据，输出信号结果。
 *
 * 信号优先级：
 * 1. 止损
 * 2. 止盈
 * 3. 均线卖出
 * 4. 均线买入
 * 5. HOLD
 */
@Slf4j
@Component
public class MaCrossStrategyEngine {

    /**
     * 生成交易信号。
     *
     * @param candles  已完成K线列表（按时间降序）
     * @param config   策略配置
     * @param position 当前持仓（可为null）
     * @return 信号结果
     */
    public SignalResult generateSignal(List<MarketCandleEntity> candles, StrategyConfigEntity config, PositionEntity position) {
        int slowPeriod = config.getSlowPeriod();

        // 需要至少 slowPeriod + 1 根K线来计算当前和上一根的均线
        if (candles.size() < slowPeriod + 1) {
            return SignalResult.hold("K线数量不足，需要至少" + (slowPeriod + 1) + "根，当前" + candles.size() + "根");
        }

        // K线按时间降序排列，反转为升序方便计算
        List<MarketCandleEntity> sorted = new ArrayList<>(candles);
        Collections.reverse(sorted);

        int size = sorted.size();
        BigDecimal currentClose = sorted.get(size - 1).getClosePrice();
        Long currentCandleTime = sorted.get(size - 1).getCandleTime();

        // 计算当前K线的快线和慢线
        BigDecimal currentFastMa = calculateMa(sorted, size - 1, config.getFastPeriod());
        BigDecimal currentSlowMa = calculateMa(sorted, size - 1, config.getSlowPeriod());

        // 计算上一根K线的快线和慢线
        BigDecimal prevFastMa = calculateMa(sorted, size - 2, config.getFastPeriod());
        BigDecimal prevSlowMa = calculateMa(sorted, size - 2, config.getSlowPeriod());

        // 优先级1：止损判断
        if (position != null && BigDecimalUtil.isPositive(position.getQuantity())) {
            BigDecimal stopLossPrice = BigDecimalUtil.multiply(
                    position.getAvgPrice(),
                    BigDecimal.ONE.subtract(config.getStopLossPct())
            );
            if (BigDecimalUtil.lte(currentClose, stopLossPrice)) {
                return SignalResult.sell(currentClose, currentFastMa, currentSlowMa, currentCandleTime,
                        "触发止损: 当前价格" + currentClose + " <= 止损价" + stopLossPrice);
            }
        }

        // 优先级2：止盈判断
        if (position != null && BigDecimalUtil.isPositive(position.getQuantity())) {
            BigDecimal takeProfitPrice = BigDecimalUtil.multiply(
                    position.getAvgPrice(),
                    BigDecimal.ONE.add(config.getTakeProfitPct())
            );
            if (BigDecimalUtil.gte(currentClose, takeProfitPrice)) {
                return SignalResult.sell(currentClose, currentFastMa, currentSlowMa, currentCandleTime,
                        "触发止盈: 当前价格" + currentClose + " >= 止盈价" + takeProfitPrice);
            }
        }

        // 优先级3：均线死叉卖出
        // 上一根K线快线 >= 慢线，当前K线快线 < 慢线
        if (BigDecimalUtil.gte(prevFastMa, prevSlowMa) && BigDecimalUtil.lt(currentFastMa, currentSlowMa)) {
            return SignalResult.sell(currentClose, currentFastMa, currentSlowMa, currentCandleTime,
                    "均线死叉: 快线" + currentFastMa + " < 慢线" + currentSlowMa);
        }

        // 优先级4：均线金叉买入
        // 上一根K线快线 <= 慢线，当前K线快线 > 慢线
        if (BigDecimalUtil.lte(prevFastMa, prevSlowMa) && BigDecimalUtil.gt(currentFastMa, currentSlowMa)) {
            return SignalResult.buy(currentClose, currentFastMa, currentSlowMa, currentCandleTime,
                    "均线金叉: 快线" + currentFastMa + " > 慢线" + currentSlowMa);
        }

        // 优先级5：无信号
        return SignalResult.hold(currentClose, currentFastMa, currentSlowMa, currentCandleTime,
                "无交叉信号: 快线" + currentFastMa + ", 慢线" + currentSlowMa);
    }

    /**
     * 计算指定位置的移动平均线。
     *
     * @param candles 升序排列的K线
     * @param endIdx  结束索引（包含）
     * @param period  均线周期
     */
    private BigDecimal calculateMa(List<MarketCandleEntity> candles, int endIdx, int period) {
        List<BigDecimal> prices = new ArrayList<>();
        for (int i = endIdx; i > endIdx - period && i >= 0; i--) {
            prices.add(candles.get(i).getClosePrice());
        }
        return BigDecimalUtil.average(prices);
    }
}
