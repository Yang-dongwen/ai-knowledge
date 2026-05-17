package com.dwcode.okxbot.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.trading.order.entity.TradeOrderEntity;
import com.dwcode.okxbot.trading.order.mapper.TradeOrderMapper;
import com.dwcode.okxbot.trading.order.service.TradeOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 订单记录接口。
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final TradeOrderMapper tradeOrderMapper;
    private final TradeOrderService tradeOrderService;

    @GetMapping
    public ApiResult<List<TradeOrderEntity>> listOrders() {
        List<TradeOrderEntity> orders = tradeOrderMapper.selectList(
                new LambdaQueryWrapper<TradeOrderEntity>()
                        .orderByDesc(TradeOrderEntity::getCreatedAt)
                        .last("LIMIT 100")
        );
        return ApiResult.ok(orders);
    }

    @GetMapping("/{id}")
    public ApiResult<TradeOrderEntity> getOrder(@PathVariable Long id) {
        return ApiResult.ok(tradeOrderMapper.selectById(id));
    }

    /**
     * 手动测试买入（模拟盘小额测试）。
     */
    @PostMapping("/test-buy")
    public ApiResult<Long> testBuy(@RequestBody Map<String, String> params) {
        String symbol = params.getOrDefault("symbol", "BTC-USDT");
        String amountStr = params.getOrDefault("amount", "1");
        BigDecimal amount = new BigDecimal(amountStr);
        Long strategyId = 0L;

        Long orderId = tradeOrderService.submitMarketBuyOrder(strategyId, symbol, amount);
        return ApiResult.ok(orderId);
    }
}
