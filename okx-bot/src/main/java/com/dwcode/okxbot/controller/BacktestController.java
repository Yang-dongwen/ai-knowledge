package com.dwcode.okxbot.controller;

import com.dwcode.okxbot.backtest.dto.BacktestRequest;
import com.dwcode.okxbot.backtest.entity.BacktestEquityCurveEntity;
import com.dwcode.okxbot.backtest.entity.BacktestTaskEntity;
import com.dwcode.okxbot.backtest.entity.BacktestTradeEntity;
import com.dwcode.okxbot.backtest.service.BacktestService;
import com.dwcode.okxbot.common.response.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 回测接口。
 *
 * 提供创建回测、查询回测结果、交易明细和资金曲线的能力。
 */
@RestController
@RequestMapping("/api/backtests")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestService backtestService;

    /**
     * 创建并执行一次回测，返回回测任务ID。
     */
    @PostMapping
    public ApiResult<Long> runBacktest(@Valid @RequestBody BacktestRequest request) {
        return ApiResult.ok(backtestService.runBacktest(request));
    }

    /**
     * 查询回测任务列表，可按策略ID过滤。
     */
    @GetMapping
    public ApiResult<List<BacktestTaskEntity>> listTasks(@RequestParam(required = false) Long strategyId) {
        return ApiResult.ok(backtestService.listTasks(strategyId));
    }

    /**
     * 查询回测任务详情（含绩效指标）。
     */
    @GetMapping("/{id}")
    public ApiResult<BacktestTaskEntity> getTask(@PathVariable Long id) {
        return ApiResult.ok(backtestService.getTask(id));
    }

    /**
     * 查询回测交易明细。
     */
    @GetMapping("/{id}/trades")
    public ApiResult<List<BacktestTradeEntity>> listTrades(@PathVariable Long id) {
        return ApiResult.ok(backtestService.listTrades(id));
    }

    /**
     * 查询回测资金曲线（含回撤）。
     */
    @GetMapping("/{id}/equity-curve")
    public ApiResult<List<BacktestEquityCurveEntity>> listEquityCurve(@PathVariable Long id) {
        return ApiResult.ok(backtestService.listEquityCurve(id));
    }
}
