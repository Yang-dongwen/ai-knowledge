package com.dwcode.okxbot.pay.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.member.service.MemberFulfillmentService;
import com.dwcode.okxbot.pay.entity.PayOrderEntity;
import com.dwcode.okxbot.pay.enums.PayOrderStatus;
import com.dwcode.okxbot.pay.mapper.PayOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 独立 Bean：SUCCESS + 履约同事务，避免 PayOrderService 自调用导致事务失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayFulfillService {

    private final PayOrderMapper payOrderMapper;
    private final MemberFulfillmentService memberFulfillmentService;

    @Transactional
    public void markSuccessAndFulfill(String orderNo, String tradeNo, long amountCentsFromChannel) {
        PayOrderEntity order = payOrderMapper.selectOne(
                new LambdaQueryWrapper<PayOrderEntity>().eq(PayOrderEntity::getOrderNo, orderNo)
        );
        if (order == null) {
            throw new BusinessException(404, "订单不存在: " + orderNo);
        }
        // 已履约成功：幂等早退（在金额复核前），避免渠道重试因解析差异刷失败
        if (PayOrderStatus.SUCCESS.equals(order.getStatus())
                && order.getFulfilled() != null && order.getFulfilled() == 1) {
            return;
        }
        if (order.getAmountCents() == null || order.getAmountCents().longValue() != amountCentsFromChannel) {
            throw new BusinessException(400, "金额不匹配");
        }

        if (!PayOrderStatus.SUCCESS.equals(order.getStatus())) {
            int n = payOrderMapper.casToSuccess(orderNo, tradeNo);
            if (n == 0) {
                PayOrderEntity again = payOrderMapper.selectOne(
                        new LambdaQueryWrapper<PayOrderEntity>().eq(PayOrderEntity::getOrderNo, orderNo)
                );
                if (again == null || !PayOrderStatus.SUCCESS.equals(again.getStatus())) {
                    throw new BusinessException(409, "订单状态不允许支付成功: " + orderNo);
                }
            }
            order = payOrderMapper.selectOne(
                    new LambdaQueryWrapper<PayOrderEntity>().eq(PayOrderEntity::getOrderNo, orderNo)
            );
        }

        int f = payOrderMapper.markFulfilledIfNot(orderNo);
        if (f == 1) {
            memberFulfillmentService.grantByOrder(
                    order.getUserId(),
                    order.getDurationDays(),
                    order.getOrderNo()
            );
        }
        log.info("markSuccessAndFulfill done orderNo={} tradeNo={} fulfilledCas={}", orderNo, tradeNo, f);
    }

    /**
     * 仅补偿履约：订单已是 SUCCESS 且 fulfilled=0。
     */
    @Transactional
    public boolean recoverUnfulfilled(String orderNo) {
        PayOrderEntity order = payOrderMapper.selectOne(
                new LambdaQueryWrapper<PayOrderEntity>().eq(PayOrderEntity::getOrderNo, orderNo)
        );
        if (order == null || !PayOrderStatus.SUCCESS.equals(order.getStatus())) {
            return false;
        }
        if (order.getFulfilled() != null && order.getFulfilled() == 1) {
            return false;
        }
        int f = payOrderMapper.markFulfilledIfNot(orderNo);
        if (f == 1) {
            memberFulfillmentService.grantByOrder(order.getUserId(), order.getDurationDays(), order.getOrderNo());
            log.warn("fulfillPending recovered orderNo={} userId={}", order.getOrderNo(), order.getUserId());
            return true;
        }
        return false;
    }
}
