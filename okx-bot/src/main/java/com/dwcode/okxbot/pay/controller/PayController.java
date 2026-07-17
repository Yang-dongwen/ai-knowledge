package com.dwcode.okxbot.pay.controller;

import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.pay.config.PayProperties;
import com.dwcode.okxbot.pay.dto.CreatePayOrderRequest;
import com.dwcode.okxbot.pay.dto.MockConfirmRequest;
import com.dwcode.okxbot.pay.dto.PayOrderResponse;
import com.dwcode.okxbot.pay.service.PayOrderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/pay")
@RequiredArgsConstructor
public class PayController {

    private final PayOrderService payOrderService;
    private final PayProperties payProperties;

    @PostMapping("/orders")
    public ApiResult<PayOrderResponse> create(@Valid @RequestBody CreatePayOrderRequest request,
                                              HttpServletRequest httpRequest) {
        Long userId = SecurityUtils.requireCurrentUserId();
        String ip = resolveClientIp(httpRequest);
        return ApiResult.ok(payOrderService.createOrder(userId, request, ip));
    }

    @GetMapping("/orders/{orderNo}")
    public ApiResult<PayOrderResponse> get(@PathVariable String orderNo) {
        Long userId = SecurityUtils.requireCurrentUserId();
        return ApiResult.ok(payOrderService.getMyOrder(userId, orderNo));
    }

    @GetMapping("/orders")
    public ApiResult<List<PayOrderResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.requireCurrentUserId();
        return ApiResult.ok(payOrderService.listMyOrders(userId, page, size));
    }

    @PostMapping("/orders/{orderNo}/cancel")
    public ApiResult<PayOrderResponse> cancel(@PathVariable String orderNo) {
        Long userId = SecurityUtils.requireCurrentUserId();
        return ApiResult.ok(payOrderService.cancelMyOrder(userId, orderNo));
    }

    /**
     * Mock 确认支付：必须登录 + pay.mock-enabled=true，禁止匿名。
     */
    @PostMapping("/mock/confirm")
    public ApiResult<PayOrderResponse> mockConfirm(@Valid @RequestBody MockConfirmRequest request) {
        Long userId = SecurityUtils.requireCurrentUserId();
        return ApiResult.ok(payOrderService.mockConfirm(userId, request.getOrderNo()));
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (payProperties.isTrustXForwardedFor()) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
