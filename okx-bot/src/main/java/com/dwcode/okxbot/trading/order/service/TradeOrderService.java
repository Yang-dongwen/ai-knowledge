package com.dwcode.okxbot.trading.order.service;

import com.dwcode.okxbot.common.enums.OrderSideEnum;
import com.dwcode.okxbot.common.enums.OrderStatusEnum;
import com.dwcode.okxbot.common.exception.OrderException;
import com.dwcode.okxbot.common.exception.SystemStoppedException;
import com.dwcode.okxbot.okx.client.OkxRestClient;
import com.dwcode.okxbot.okx.service.OkxConfigService;
import com.dwcode.okxbot.system.service.SystemStateService;
import com.dwcode.okxbot.trading.order.entity.TradeOrderEntity;
import com.dwcode.okxbot.trading.order.mapper.TradeOrderMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 交易订单服务。
 *
 * 职责：
 * 1. 根据 BUY 信号买入
 * 2. 根据 SELL 信号卖出
 * 3. 生成 client_order_id
 * 4. 先落库，再调用 OKX
 * 5. 保存订单结果
 *
 * 注意：
 * 所有下单必须先落库，再请求 OKX。
 * 市价单失败不允许无限重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeOrderService {

    private final TradeOrderMapper tradeOrderMapper;
    private final OkxRestClient okxRestClient;
    private final OkxConfigService okxConfigService;
    private final SystemStateService systemStateService;
    private final ObjectMapper objectMapper;

    /**
     * 提交市价买入订单。
     *
     * @param strategyId 策略ID
     * @param symbol     交易对
     * @param notional   买入金额(USDT)
     * @return 订单ID
     */
    public Long submitMarketBuyOrder(Long strategyId, String symbol, BigDecimal notional) {
        // 检查系统是否停止
        if (systemStateService.isStopped()) {
            throw new SystemStoppedException();
        }

        String clientOrderId = generateClientOrderId();

        // 1. 订单入库，状态 CREATED
        TradeOrderEntity order = new TradeOrderEntity();
        order.setStrategyId(strategyId);
        order.setSymbol(symbol);
        order.setSide(OrderSideEnum.BUY.name());
        order.setOrderType("MARKET");
        order.setNotional(notional);
        order.setClientOrderId(clientOrderId);
        order.setStatus(OrderStatusEnum.CREATED.name());
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        tradeOrderMapper.insert(order);

        // 2. 更新状态为 SUBMITTING
        order.setStatus(OrderStatusEnum.SUBMITTING.name());
        order.setUpdatedAt(LocalDateTime.now());
        tradeOrderMapper.updateById(order);

        // 3. 调用 OKX 下单
        try {
            String[] credentials = okxConfigService.getDecryptedCredentials();

            // 市价买入按USDT金额计算，使用 sz 表示金额，tgtCcy = quote_ccy
            Map<String, String> params = new HashMap<>();
            params.put("instId", symbol);
            params.put("tdMode", "cash");
            params.put("side", "buy");
            params.put("ordType", "market");
            params.put("sz", notional.toPlainString());
            params.put("tgtCcy", "quote_ccy");
            params.put("clOrdId", clientOrderId);

            String body = objectMapper.writeValueAsString(params);
            order.setRawRequest(body);

            JsonNode result = okxRestClient.post("/api/v5/trade/order", body, credentials[0], credentials[1], credentials[2]);

            String rawResponse = objectMapper.writeValueAsString(result);
            order.setRawResponse(rawResponse);

            // 解析 OKX 订单ID
            JsonNode dataNode = result.path("data");
            if (dataNode.isArray() && !dataNode.isEmpty()) {
                String okxOrderId = dataNode.get(0).path("ordId").asText();
                order.setOkxOrderId(okxOrderId);
            }

            order.setStatus(OrderStatusEnum.SUBMITTED.name());
            order.setUpdatedAt(LocalDateTime.now());
            tradeOrderMapper.updateById(order);

            log.info("买入订单提交成功: clientOrderId={}, symbol={}, notional={}", clientOrderId, symbol, notional);
            return order.getId();

        } catch (Exception e) {
            order.setStatus(OrderStatusEnum.FAILED.name());
            order.setErrorMessage(e.getMessage());
            order.setUpdatedAt(LocalDateTime.now());
            tradeOrderMapper.updateById(order);
            log.error("买入订单提交失败: clientOrderId={}, error={}", clientOrderId, e.getMessage());
            throw new OrderException("买入订单提交失败: " + e.getMessage());
        }
    }

    /**
     * 提交市价卖出订单。
     *
     * @param strategyId 策略ID
     * @param symbol     交易对
     * @param quantity   卖出数量
     * @return 订单ID
     */
    public Long submitMarketSellOrder(Long strategyId, String symbol, BigDecimal quantity) {
        // 检查系统是否停止
        if (systemStateService.isStopped()) {
            throw new SystemStoppedException();
        }

        String clientOrderId = generateClientOrderId();

        // 1. 订单入库，状态 CREATED
        TradeOrderEntity order = new TradeOrderEntity();
        order.setStrategyId(strategyId);
        order.setSymbol(symbol);
        order.setSide(OrderSideEnum.SELL.name());
        order.setOrderType("MARKET");
        order.setQuantity(quantity);
        order.setClientOrderId(clientOrderId);
        order.setStatus(OrderStatusEnum.CREATED.name());
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        tradeOrderMapper.insert(order);

        // 2. 更新状态为 SUBMITTING
        order.setStatus(OrderStatusEnum.SUBMITTING.name());
        order.setUpdatedAt(LocalDateTime.now());
        tradeOrderMapper.updateById(order);

        // 3. 调用 OKX 下单
        try {
            String[] credentials = okxConfigService.getDecryptedCredentials();

            // 市价卖出按持仓币数量计算，使用 sz 表示数量，tgtCcy = base_ccy
            Map<String, String> params = new HashMap<>();
            params.put("instId", symbol);
            params.put("tdMode", "cash");
            params.put("side", "sell");
            params.put("ordType", "market");
            params.put("sz", quantity.toPlainString());
            params.put("tgtCcy", "base_ccy");
            params.put("clOrdId", clientOrderId);

            String body = objectMapper.writeValueAsString(params);
            order.setRawRequest(body);

            JsonNode result = okxRestClient.post("/api/v5/trade/order", body, credentials[0], credentials[1], credentials[2]);

            String rawResponse = objectMapper.writeValueAsString(result);
            order.setRawResponse(rawResponse);

            JsonNode dataNode = result.path("data");
            if (dataNode.isArray() && !dataNode.isEmpty()) {
                String okxOrderId = dataNode.get(0).path("ordId").asText();
                order.setOkxOrderId(okxOrderId);
            }

            order.setStatus(OrderStatusEnum.SUBMITTED.name());
            order.setUpdatedAt(LocalDateTime.now());
            tradeOrderMapper.updateById(order);

            log.info("卖出订单提交成功: clientOrderId={}, symbol={}, quantity={}", clientOrderId, symbol, quantity);
            return order.getId();

        } catch (Exception e) {
            order.setStatus(OrderStatusEnum.FAILED.name());
            order.setErrorMessage(e.getMessage());
            order.setUpdatedAt(LocalDateTime.now());
            tradeOrderMapper.updateById(order);
            log.error("卖出订单提交失败: clientOrderId={}, error={}", clientOrderId, e.getMessage());
            throw new OrderException("卖出订单提交失败: " + e.getMessage());
        }
    }

    /**
     * 生成唯一的客户端订单ID。
     */
    private String generateClientOrderId() {
        return "bot" + UUID.randomUUID().toString().replace("-", "").substring(0, 29);
    }
}
