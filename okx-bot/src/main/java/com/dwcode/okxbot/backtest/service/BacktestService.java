package com.dwcode.okxbot.backtest.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.backtest.dto.BacktestRequest;
import com.dwcode.okxbot.backtest.dto.BacktestRunResult;
import com.dwcode.okxbot.backtest.engine.BacktestEngine;
import com.dwcode.okxbot.backtest.entity.BacktestEquityCurveEntity;
import com.dwcode.okxbot.backtest.entity.BacktestTaskEntity;
import com.dwcode.okxbot.backtest.entity.BacktestTradeEntity;
import com.dwcode.okxbot.backtest.mapper.BacktestEquityCurveMapper;
import com.dwcode.okxbot.backtest.mapper.BacktestTaskMapper;
import com.dwcode.okxbot.backtest.mapper.BacktestTradeMapper;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.market.entity.MarketCandleEntity;
import com.dwcode.okxbot.market.service.MarketCandleService;
import com.dwcode.okxbot.strategy.entity.StrategyConfigEntity;
import com.dwcode.okxbot.strategy.service.StrategyConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回测服务。
 *
 * 职责：
 * 1. 校验回测输入
 * 2. 加载策略配置和历史已完成K线
 * 3. 调用回测引擎
 * 4. 落库回测任务、交易明细、资金曲线
 * 5. 提供回测结果查询
 *
 * 注意：
 * 本类不直接计算策略信号，信号计算在 BacktestEngine + MaCrossStrategyEngine 完成。
 * 回测只读历史数据，不调用 OKX，不触发任何真实下单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    /** 回测错误业务码 */
    private static final int BACKTEST_ERROR = 21001;

    private final BacktestTaskMapper backtestTaskMapper;
    private final BacktestTradeMapper backtestTradeMapper;
    private final BacktestEquityCurveMapper backtestEquityCurveMapper;
    private final StrategyConfigService strategyConfigService;
    private final MarketCandleService marketCandleService;
    private final BacktestEngine backtestEngine;
    private final ObjectMapper objectMapper;

    /**
     * 创建并同步执行一次回测。
     *
     * 第一版回测为同步执行（数据量可控），完成后直接返回任务ID。
     * 后续数据量变大可改为异步任务 + 状态轮询。
     *
     * @return 回测任务ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long runBacktest(BacktestRequest request) {
        StrategyConfigEntity config = strategyConfigService.getStrategyById(request.getStrategyId());

        // 1. 先落库回测任务，状态 RUNNING（先记录，保证可追溯）
        BacktestTaskEntity task = buildTask(request, config);
        task.setStatus("RUNNING");
        task.setStartedAt(LocalDateTime.now());
        backtestTaskMapper.insert(task);

        try {
            // 2. 加载回测区间内的已完成K线（升序）
            List<MarketCandleEntity> candles = marketCandleService.getConfirmedCandlesInRange(
                    config.getSymbol(), config.getTimeframe(), request.getStartTime(), request.getEndTime());

            int warmup = config.getSlowPeriod() + 1;
            if (candles.size() <= warmup) {
                throw new BusinessException(BACKTEST_ERROR,
                        "回测区间内已完成K线不足，至少需要" + (warmup + 1) + "根，当前" + candles.size()
                                + "根。请先同步足够的历史K线。");
            }

            // 3. 执行回测
            BacktestRunResult result = backtestEngine.run(
                    candles, config, request.getInitialCapital(),
                    request.getFeeRate(), request.getSlippageRate());

            // 4. 落库交易明细与资金曲线
            persistDetails(task.getId(), result);

            // 5. 回写绩效指标
            fillTaskResult(task, result);
            task.setStatus("SUCCESS");
            task.setFinishedAt(LocalDateTime.now());
            backtestTaskMapper.updateById(task);

            log.info("回测完成: taskId={}, strategyId={}, 总收益率={}, 最大回撤={}, 交易次数={}",
                    task.getId(), config.getId(), result.getTotalReturn(),
                    result.getMaxDrawdown(), result.getTradeCount());

            return task.getId();

        } catch (BusinessException e) {
            // 业务校验类错误：标记失败并抛出（事务回滚）
            log.warn("回测失败: taskId={}, reason={}", task.getId(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("回测异常: taskId={}, error={}", task.getId(), e.getMessage(), e);
            throw new BusinessException(BACKTEST_ERROR, "回测执行异常: " + e.getMessage());
        }
    }

    /**
     * 查询回测任务详情。
     */
    public BacktestTaskEntity getTask(Long taskId) {
        BacktestTaskEntity task = backtestTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(BACKTEST_ERROR, "回测任务不存在: " + taskId);
        }
        return task;
    }

    /**
     * 查询某策略的回测任务列表（按创建时间倒序）。
     */
    public List<BacktestTaskEntity> listTasks(Long strategyId) {
        LambdaQueryWrapper<BacktestTaskEntity> wrapper = new LambdaQueryWrapper<BacktestTaskEntity>()
                .orderByDesc(BacktestTaskEntity::getCreatedAt);
        if (strategyId != null) {
            wrapper.eq(BacktestTaskEntity::getStrategyId, strategyId);
        }
        return backtestTaskMapper.selectList(wrapper);
    }

    /**
     * 查询回测交易明细。
     */
    public List<BacktestTradeEntity> listTrades(Long taskId) {
        return backtestTradeMapper.selectList(
                new LambdaQueryWrapper<BacktestTradeEntity>()
                        .eq(BacktestTradeEntity::getBacktestTaskId, taskId)
                        .orderByAsc(BacktestTradeEntity::getEntryTime));
    }

    /**
     * 查询回测资金曲线。
     */
    public List<BacktestEquityCurveEntity> listEquityCurve(Long taskId) {
        return backtestEquityCurveMapper.selectList(
                new LambdaQueryWrapper<BacktestEquityCurveEntity>()
                        .eq(BacktestEquityCurveEntity::getBacktestTaskId, taskId)
                        .orderByAsc(BacktestEquityCurveEntity::getCandleTime));
    }

    // ---------------------- 内部方法 ----------------------

    private BacktestTaskEntity buildTask(BacktestRequest request, StrategyConfigEntity config) {
        BacktestTaskEntity task = new BacktestTaskEntity();
        task.setStrategyId(config.getId());
        task.setSymbol(config.getSymbol());
        task.setTimeframe(config.getTimeframe());
        task.setStartTime(request.getStartTime());
        task.setEndTime(request.getEndTime());
        task.setInitialCapital(request.getInitialCapital());
        task.setFeeRate(request.getFeeRate());
        task.setSlippageRate(request.getSlippageRate());
        return task;
    }

    private void persistDetails(Long taskId, BacktestRunResult result) {
        for (BacktestTradeEntity trade : result.getTrades()) {
            trade.setBacktestTaskId(taskId);
            backtestTradeMapper.insert(trade);
        }
        for (BacktestEquityCurveEntity point : result.getEquityCurve()) {
            point.setBacktestTaskId(taskId);
            backtestEquityCurveMapper.insert(point);
        }
    }

    private void fillTaskResult(BacktestTaskEntity task, BacktestRunResult result) {
        task.setFinalEquity(result.getFinalEquity());
        task.setTotalReturn(result.getTotalReturn());
        task.setAnnualReturn(result.getAnnualReturn());
        task.setMaxDrawdown(result.getMaxDrawdown());
        task.setSharpeRatio(result.getSharpeRatio());
        task.setWinRate(result.getWinRate());
        task.setProfitFactor(result.getProfitFactor());
        task.setTradeCount(result.getTradeCount());
        task.setMaxConsecutiveLosses(result.getMaxConsecutiveLosses());
        task.setTotalFee(result.getTotalFee());
        task.setTotalSlippageCost(result.getTotalSlippageCost());
        task.setBarCount(result.getBarCount());
        task.setResultSummary(buildSummaryJson(result));
    }

    /**
     * 构造结果摘要JSON，便于前端一次性展示核心指标。
     */
    private String buildSummaryJson(BacktestRunResult result) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("finalEquity", result.getFinalEquity());
            node.put("totalReturn", result.getTotalReturn());
            node.put("annualReturn", result.getAnnualReturn());
            node.put("maxDrawdown", result.getMaxDrawdown());
            node.put("sharpeRatio", result.getSharpeRatio());
            node.put("winRate", result.getWinRate());
            node.put("profitFactor", result.getProfitFactor());
            node.put("tradeCount", result.getTradeCount());
            node.put("maxConsecutiveLosses", result.getMaxConsecutiveLosses());
            node.put("totalFee", result.getTotalFee());
            node.put("totalSlippageCost", result.getTotalSlippageCost());
            node.put("barCount", result.getBarCount());
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("构造回测摘要JSON失败: {}", e.getMessage());
            return null;
        }
    }
}
