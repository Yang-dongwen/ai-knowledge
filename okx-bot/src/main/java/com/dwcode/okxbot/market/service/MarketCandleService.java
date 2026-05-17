package com.dwcode.okxbot.market.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.market.entity.MarketCandleEntity;
import com.dwcode.okxbot.market.mapper.MarketCandleMapper;
import com.dwcode.okxbot.okx.client.OkxRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 行情 K 线服务。
 *
 * 职责：
 * 1. 从 OKX 拉取 K 线
 * 2. 保存 K 线到数据库（去重）
 * 3. 查询最近 K 线
 * 4. 过滤未完成 K 线
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketCandleService {

    private final MarketCandleMapper marketCandleMapper;
    private final OkxRestClient okxRestClient;

    /**
     * 同步最近 K 线数据。
     *
     * @param symbol    交易对，如 BTC-USDT
     * @param timeframe K 线周期，如 1H
     * @param limit     获取数量
     */
    public void syncRecentCandles(String symbol, String timeframe, int limit) {
        log.info("开始同步K线: symbol={}, timeframe={}, limit={}", symbol, timeframe, limit);

        String path = String.format("/api/v5/market/candles?instId=%s&bar=%s&limit=%d", symbol, timeframe, limit);
        JsonNode result = okxRestClient.getPublic(path);
        JsonNode data = result.path("data");

        if (!data.isArray() || data.isEmpty()) {
            log.warn("未获取到K线数据: symbol={}, timeframe={}", symbol, timeframe);
            return;
        }

        int insertCount = 0;
        for (JsonNode candle : data) {
            // OKX K线格式: [ts, o, h, l, c, vol, volCcy, volCcyQuote, confirm]
            Long candleTime = candle.get(0).asLong();
            BigDecimal open = new BigDecimal(candle.get(1).asText());
            BigDecimal high = new BigDecimal(candle.get(2).asText());
            BigDecimal low = new BigDecimal(candle.get(3).asText());
            BigDecimal close = new BigDecimal(candle.get(4).asText());
            BigDecimal volume = new BigDecimal(candle.get(5).asText());
            int confirmed = "1".equals(candle.get(8).asText()) ? 1 : 0;

            // 去重：检查是否已存在
            boolean exists = marketCandleMapper.selectCount(
                    new LambdaQueryWrapper<MarketCandleEntity>()
                            .eq(MarketCandleEntity::getSymbol, symbol)
                            .eq(MarketCandleEntity::getTimeframe, timeframe)
                            .eq(MarketCandleEntity::getCandleTime, candleTime)
            ) > 0;

            if (exists) {
                // 已存在则更新 confirmed 状态
                MarketCandleEntity existing = marketCandleMapper.selectOne(
                        new LambdaQueryWrapper<MarketCandleEntity>()
                                .eq(MarketCandleEntity::getSymbol, symbol)
                                .eq(MarketCandleEntity::getTimeframe, timeframe)
                                .eq(MarketCandleEntity::getCandleTime, candleTime)
                );
                if (existing != null && existing.getConfirmed() == 0 && confirmed == 1) {
                    existing.setClosePrice(close);
                    existing.setHighPrice(high);
                    existing.setLowPrice(low);
                    existing.setVolume(volume);
                    existing.setConfirmed(1);
                    existing.setUpdatedAt(LocalDateTime.now());
                    marketCandleMapper.updateById(existing);
                }
                continue;
            }

            MarketCandleEntity entity = new MarketCandleEntity();
            entity.setSymbol(symbol);
            entity.setTimeframe(timeframe);
            entity.setCandleTime(candleTime);
            entity.setOpenPrice(open);
            entity.setHighPrice(high);
            entity.setLowPrice(low);
            entity.setClosePrice(close);
            entity.setVolume(volume);
            entity.setConfirmed(confirmed);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            marketCandleMapper.insert(entity);
            insertCount++;
        }

        log.info("K线同步完成: symbol={}, timeframe={}, 新增={}", symbol, timeframe, insertCount);
    }

    /**
     * 查询最近已完成的 K 线。
     */
    public List<MarketCandleEntity> getRecentConfirmedCandles(String symbol, String timeframe, int limit) {
        return marketCandleMapper.selectList(
                new LambdaQueryWrapper<MarketCandleEntity>()
                        .eq(MarketCandleEntity::getSymbol, symbol)
                        .eq(MarketCandleEntity::getTimeframe, timeframe)
                        .eq(MarketCandleEntity::getConfirmed, 1)
                        .orderByDesc(MarketCandleEntity::getCandleTime)
                        .last("LIMIT " + limit)
        );
    }
}
