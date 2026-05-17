package com.dwcode.okxbot.strategy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.common.exception.StrategyException;
import com.dwcode.okxbot.strategy.dto.CreateStrategyRequest;
import com.dwcode.okxbot.strategy.entity.StrategyConfigEntity;
import com.dwcode.okxbot.strategy.mapper.StrategyConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 策略配置服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyConfigService {

    private final StrategyConfigMapper strategyConfigMapper;

    /**
     * 创建策略。
     */
    public Long createStrategy(CreateStrategyRequest request) {
        // 参数校验
        if (request.getFastPeriod() >= request.getSlowPeriod()) {
            throw new StrategyException(20002, "快线周期必须小于慢线周期");
        }

        StrategyConfigEntity entity = new StrategyConfigEntity();
        entity.setStrategyName(request.getStrategyName());
        entity.setSymbol(request.getSymbol());
        entity.setTimeframe(request.getTimeframe());
        entity.setFastPeriod(request.getFastPeriod());
        entity.setSlowPeriod(request.getSlowPeriod());
        entity.setTradeAmountPct(request.getTradeAmountPct());
        entity.setStopLossPct(request.getStopLossPct());
        entity.setTakeProfitPct(request.getTakeProfitPct());
        entity.setEnabled(0);
        entity.setRunMode(request.getRunMode() != null ? request.getRunMode() : "PAPER");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        strategyConfigMapper.insert(entity);
        log.info("创建策略: id={}, name={}", entity.getId(), entity.getStrategyName());
        return entity.getId();
    }

    /**
     * 修改策略。
     */
    public void updateStrategy(Long id, CreateStrategyRequest request) {
        StrategyConfigEntity entity = getStrategyById(id);

        if (request.getFastPeriod() >= request.getSlowPeriod()) {
            throw new StrategyException(20002, "快线周期必须小于慢线周期");
        }

        entity.setStrategyName(request.getStrategyName());
        entity.setSymbol(request.getSymbol());
        entity.setTimeframe(request.getTimeframe());
        entity.setFastPeriod(request.getFastPeriod());
        entity.setSlowPeriod(request.getSlowPeriod());
        entity.setTradeAmountPct(request.getTradeAmountPct());
        entity.setStopLossPct(request.getStopLossPct());
        entity.setTakeProfitPct(request.getTakeProfitPct());
        if (request.getRunMode() != null) {
            entity.setRunMode(request.getRunMode());
        }
        entity.setUpdatedAt(LocalDateTime.now());

        strategyConfigMapper.updateById(entity);
        log.info("修改策略: id={}", id);
    }

    /**
     * 启用策略。
     */
    public void enableStrategy(Long id) {
        StrategyConfigEntity entity = getStrategyById(id);
        entity.setEnabled(1);
        entity.setUpdatedAt(LocalDateTime.now());
        strategyConfigMapper.updateById(entity);
        log.info("启用策略: id={}, name={}", id, entity.getStrategyName());
    }

    /**
     * 停用策略。
     */
    public void disableStrategy(Long id) {
        StrategyConfigEntity entity = getStrategyById(id);
        entity.setEnabled(0);
        entity.setUpdatedAt(LocalDateTime.now());
        strategyConfigMapper.updateById(entity);
        log.info("停用策略: id={}, name={}", id, entity.getStrategyName());
    }

    /**
     * 删除策略。
     */
    public void deleteStrategy(Long id) {
        StrategyConfigEntity entity = getStrategyById(id);
        if (entity.getEnabled() == 1) {
            throw new StrategyException("不能删除启用中的策略，请先停用");
        }
        strategyConfigMapper.deleteById(id);
        log.info("删除策略: id={}", id);
    }

    /**
     * 查询所有启用的策略。
     */
    public List<StrategyConfigEntity> getEnabledStrategies() {
        return strategyConfigMapper.selectList(
                new LambdaQueryWrapper<StrategyConfigEntity>()
                        .eq(StrategyConfigEntity::getEnabled, 1)
        );
    }

    /**
     * 查询策略列表。
     */
    public List<StrategyConfigEntity> listStrategies() {
        return strategyConfigMapper.selectList(
                new LambdaQueryWrapper<StrategyConfigEntity>()
                        .orderByDesc(StrategyConfigEntity::getCreatedAt)
        );
    }

    /**
     * 查询策略详情。
     */
    public StrategyConfigEntity getStrategyById(Long id) {
        StrategyConfigEntity entity = strategyConfigMapper.selectById(id);
        if (entity == null) {
            throw new StrategyException("策略不存在: " + id);
        }
        return entity;
    }

    /**
     * 更新最近运行K线时间。
     */
    public void updateLastRunCandleTime(Long strategyId, Long candleTime) {
        StrategyConfigEntity entity = strategyConfigMapper.selectById(strategyId);
        if (entity != null) {
            entity.setLastRunCandleTime(candleTime);
            entity.setUpdatedAt(LocalDateTime.now());
            strategyConfigMapper.updateById(entity);
        }
    }
}
