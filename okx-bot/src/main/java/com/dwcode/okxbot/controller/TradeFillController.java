package com.dwcode.okxbot.controller;

import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.trading.fill.entity.TradeFillEntity;
import com.dwcode.okxbot.trading.fill.service.TradeFillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 交易记录（成交记录）接口。
 */
@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class TradeFillController {

    private final TradeFillService tradeFillService;

    @GetMapping
    public ApiResult<List<TradeFillEntity>> listTrades(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String side,
            @RequestParam(required = false) Long strategyId) {
        return ApiResult.ok(tradeFillService.listFills(symbol, side, strategyId));
    }

    @GetMapping("/recent")
    public ApiResult<List<TradeFillEntity>> getRecentTrades(
            @RequestParam(defaultValue = "5") int limit) {
        return ApiResult.ok(tradeFillService.getRecentFills(limit));
    }

    @GetMapping("/summary")
    public ApiResult<Map<String, Object>> getSummary() {
        return ApiResult.ok(tradeFillService.getSummary());
    }
}
