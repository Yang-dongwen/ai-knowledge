package com.dwcode.okxbot.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.strategy.entity.StrategyRunLogEntity;
import com.dwcode.okxbot.strategy.mapper.StrategyRunLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 策略运行日志接口。
 */
@RestController
@RequestMapping("/api/strategy-run-logs")
@RequiredArgsConstructor
public class StrategyLogController {

    private final StrategyRunLogMapper strategyRunLogMapper;

    @GetMapping
    public ApiResult<List<StrategyRunLogEntity>> listLogs(
            @RequestParam(required = false) Long strategyId) {
        LambdaQueryWrapper<StrategyRunLogEntity> wrapper = new LambdaQueryWrapper<StrategyRunLogEntity>()
                .orderByDesc(StrategyRunLogEntity::getCreatedAt)
                .last("LIMIT 100");

        if (strategyId != null) {
            wrapper.eq(StrategyRunLogEntity::getStrategyId, strategyId);
        }

        return ApiResult.ok(strategyRunLogMapper.selectList(wrapper));
    }

    @GetMapping("/latest")
    public ApiResult<StrategyRunLogEntity> getLatest() {
        StrategyRunLogEntity latest = strategyRunLogMapper.selectOne(
                new LambdaQueryWrapper<StrategyRunLogEntity>()
                        .orderByDesc(StrategyRunLogEntity::getCreatedAt)
                        .last("LIMIT 1")
        );
        return ApiResult.ok(latest);
    }
}
