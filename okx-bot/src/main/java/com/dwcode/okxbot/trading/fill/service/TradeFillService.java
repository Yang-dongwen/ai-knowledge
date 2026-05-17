package com.dwcode.okxbot.trading.fill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.trading.fill.entity.TradeFillEntity;
import com.dwcode.okxbot.trading.fill.mapper.TradeFillMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 成交记录服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeFillService {

    private final TradeFillMapper tradeFillMapper;

    /**
     * 查询成交记录列表。
     */
    public List<TradeFillEntity> listFills(String symbol, String side, Long strategyId) {
        LambdaQueryWrapper<TradeFillEntity> wrapper = new LambdaQueryWrapper<TradeFillEntity>()
                .orderByDesc(TradeFillEntity::getTradeTime)
                .last("LIMIT 200");

        if (symbol != null && !symbol.isEmpty()) {
            wrapper.eq(TradeFillEntity::getSymbol, symbol);
        }
        if (side != null && !side.isEmpty()) {
            wrapper.eq(TradeFillEntity::getSide, side);
        }
        if (strategyId != null) {
            wrapper.eq(TradeFillEntity::getStrategyId, strategyId);
        }

        return tradeFillMapper.selectList(wrapper);
    }

    /**
     * 查询最近成交记录。
     */
    public List<TradeFillEntity> getRecentFills(int limit) {
        return tradeFillMapper.selectList(
                new LambdaQueryWrapper<TradeFillEntity>()
                        .orderByDesc(TradeFillEntity::getTradeTime)
                        .last("LIMIT " + limit)
        );
    }

    /**
     * 查询成交统计摘要。
     */
    public Map<String, Object> getSummary() {
        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);

        List<TradeFillEntity> todayFills = tradeFillMapper.selectList(
                new LambdaQueryWrapper<TradeFillEntity>()
                        .ge(TradeFillEntity::getTradeTime, todayStart)
        );

        int todayCount = todayFills.size();
        BigDecimal todayBuyAmount = todayFills.stream()
                .filter(f -> "BUY".equals(f.getSide()))
                .map(f -> f.getNotional() != null ? f.getNotional() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal todaySellAmount = todayFills.stream()
                .filter(f -> "SELL".equals(f.getSide()))
                .map(f -> f.getNotional() != null ? f.getNotional() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal todayRealizedPnl = todayFills.stream()
                .map(f -> f.getRealizedPnl() != null ? f.getRealizedPnl() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFee = todayFills.stream()
                .map(f -> f.getFee() != null ? f.getFee() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> summary = new HashMap<>();
        summary.put("todayCount", todayCount);
        summary.put("todayBuyAmount", todayBuyAmount);
        summary.put("todaySellAmount", todaySellAmount);
        summary.put("todayRealizedPnl", todayRealizedPnl);
        summary.put("totalFee", totalFee);
        return summary;
    }
}
