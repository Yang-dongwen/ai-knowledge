package com.dwcode.okxbot.trading.position.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.common.util.BigDecimalUtil;
import com.dwcode.okxbot.trading.position.entity.PositionEntity;
import com.dwcode.okxbot.trading.position.mapper.PositionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 持仓服务。
 *
 * 职责：
 * 1. 买入后更新持仓
 * 2. 卖出后更新持仓
 * 3. 计算浮动盈亏
 * 4. 判断是否有持仓
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionService {

    private final PositionMapper positionMapper;

    /**
     * 查询策略持仓。
     */
    public PositionEntity getPosition(Long strategyId, String symbol) {
        return positionMapper.selectOne(
                new LambdaQueryWrapper<PositionEntity>()
                        .eq(PositionEntity::getStrategyId, strategyId)
                        .eq(PositionEntity::getSymbol, symbol)
        );
    }

    /**
     * 判断是否有持仓。
     */
    public boolean hasPosition(Long strategyId, String symbol) {
        PositionEntity position = getPosition(strategyId, symbol);
        return position != null && BigDecimalUtil.isPositive(position.getQuantity());
    }

    /**
     * 买入后更新持仓。
     *
     * @param strategyId 策略ID
     * @param symbol     交易对
     * @param quantity   买入数量
     * @param price      买入价格
     */
    public void updatePositionAfterBuy(Long strategyId, String symbol, BigDecimal quantity, BigDecimal price) {
        PositionEntity position = getPosition(strategyId, symbol);

        if (position == null) {
            position = new PositionEntity();
            position.setStrategyId(strategyId);
            position.setSymbol(symbol);
            position.setQuantity(quantity);
            position.setAvgPrice(price);
            position.setCurrentPrice(price);
            position.setRealizedPnl(BigDecimal.ZERO);
            position.setUnrealizedPnl(BigDecimal.ZERO);
            position.setStatus("OPEN");
            position.setCreatedAt(LocalDateTime.now());
            position.setUpdatedAt(LocalDateTime.now());
            positionMapper.insert(position);
        } else {
            // 加仓：计算新的平均成本
            BigDecimal totalCost = BigDecimalUtil.multiply(position.getAvgPrice(), position.getQuantity())
                    .add(BigDecimalUtil.multiply(price, quantity));
            BigDecimal totalQuantity = position.getQuantity().add(quantity);
            BigDecimal newAvgPrice = BigDecimalUtil.divide(totalCost, totalQuantity);

            position.setQuantity(totalQuantity);
            position.setAvgPrice(newAvgPrice);
            position.setCurrentPrice(price);
            position.setStatus("OPEN");
            position.setUpdatedAt(LocalDateTime.now());
            positionMapper.updateById(position);
        }

        log.info("买入后更新持仓: strategyId={}, symbol={}, quantity={}, price={}", strategyId, symbol, quantity, price);
    }

    /**
     * 卖出后更新持仓。
     *
     * @param strategyId 策略ID
     * @param symbol     交易对
     * @param quantity   卖出数量
     * @param price      卖出价格
     */
    public void updatePositionAfterSell(Long strategyId, String symbol, BigDecimal quantity, BigDecimal price) {
        PositionEntity position = getPosition(strategyId, symbol);
        if (position == null) {
            return;
        }

        // 计算已实现盈亏
        BigDecimal pnl = BigDecimalUtil.multiply(price.subtract(position.getAvgPrice()), quantity);
        position.setRealizedPnl(position.getRealizedPnl().add(pnl));

        BigDecimal remainingQuantity = position.getQuantity().subtract(quantity);
        if (BigDecimalUtil.lte(remainingQuantity, BigDecimal.ZERO)) {
            // 全部卖出
            position.setQuantity(BigDecimal.ZERO);
            position.setStatus("CLOSED");
        } else {
            position.setQuantity(remainingQuantity);
        }

        position.setCurrentPrice(price);
        position.setUpdatedAt(LocalDateTime.now());
        positionMapper.updateById(position);

        log.info("卖出后更新持仓: strategyId={}, symbol={}, quantity={}, price={}, pnl={}", strategyId, symbol, quantity, price, pnl);
    }

    /**
     * 查询所有持仓。
     */
    public List<PositionEntity> listPositions() {
        return positionMapper.selectList(
                new LambdaQueryWrapper<PositionEntity>()
                        .eq(PositionEntity::getStatus, "OPEN")
                        .orderByDesc(PositionEntity::getUpdatedAt)
        );
    }
}
