package com.dwcode.okxbot.controller;

import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.strategy.dto.CreateStrategyRequest;
import com.dwcode.okxbot.strategy.entity.StrategyConfigEntity;
import com.dwcode.okxbot.strategy.service.StrategyConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 策略配置接口。
 */
@RestController
@RequestMapping("/api/strategies")
@RequiredArgsConstructor
public class StrategyController {

    private final StrategyConfigService strategyConfigService;

    @PostMapping
    public ApiResult<Long> createStrategy(@Valid @RequestBody CreateStrategyRequest request) {
        Long id = strategyConfigService.createStrategy(request);
        return ApiResult.ok(id);
    }

    @PutMapping("/{id}")
    public ApiResult<Void> updateStrategy(@PathVariable Long id, @Valid @RequestBody CreateStrategyRequest request) {
        strategyConfigService.updateStrategy(id, request);
        return ApiResult.ok();
    }

    @GetMapping
    public ApiResult<List<StrategyConfigEntity>> listStrategies() {
        return ApiResult.ok(strategyConfigService.listStrategies());
    }

    @GetMapping("/{id}")
    public ApiResult<StrategyConfigEntity> getStrategy(@PathVariable Long id) {
        return ApiResult.ok(strategyConfigService.getStrategyById(id));
    }

    @PostMapping("/{id}/enable")
    public ApiResult<Void> enableStrategy(@PathVariable Long id) {
        strategyConfigService.enableStrategy(id);
        return ApiResult.ok();
    }

    @PostMapping("/{id}/disable")
    public ApiResult<Void> disableStrategy(@PathVariable Long id) {
        strategyConfigService.disableStrategy(id);
        return ApiResult.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> deleteStrategy(@PathVariable Long id) {
        strategyConfigService.deleteStrategy(id);
        return ApiResult.ok();
    }
}
