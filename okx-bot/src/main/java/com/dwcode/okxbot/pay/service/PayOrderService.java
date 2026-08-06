package com.dwcode.okxbot.pay.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dwcode.okxbot.auth.entity.SysUserEntity;
import com.dwcode.okxbot.auth.enums.UserRole;
import com.dwcode.okxbot.auth.mapper.SysUserMapper;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.member.entity.MemberPlanEntity;
import com.dwcode.okxbot.member.service.MemberPlanService;
import com.dwcode.okxbot.member.service.MemberStatusService;
import com.dwcode.okxbot.pay.channel.ChannelTradeQueryResult;
import com.dwcode.okxbot.pay.channel.PayCreateContext;
import com.dwcode.okxbot.pay.channel.PaymentChannel;
import com.dwcode.okxbot.pay.channel.PaymentChannelRegistry;
import com.dwcode.okxbot.pay.channel.PaymentCreateResult;
import com.dwcode.okxbot.pay.config.PayProperties;
import com.dwcode.okxbot.pay.dto.CreatePayOrderRequest;
import com.dwcode.okxbot.pay.dto.PayOrderResponse;
import com.dwcode.okxbot.pay.entity.PayOrderEntity;
import com.dwcode.okxbot.pay.enums.PayChannel;
import com.dwcode.okxbot.pay.enums.PayOrderStatus;
import com.dwcode.okxbot.pay.mapper.PayOrderMapper;
import com.dwcode.okxbot.pay.util.MoneyUtil;
import com.dwcode.okxbot.pay.util.OrderNoGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PayOrderService {

    private final PayOrderMapper payOrderMapper;
    private final SysUserMapper sysUserMapper;
    private final MemberPlanService memberPlanService;
    private final PayFulfillService payFulfillService;
    private final PaymentChannelRegistry channelRegistry;
    private final PayProperties payProperties;

    public void requirePayEnabled() {
        if (!payProperties.isEnabled()) {
            throw new BusinessException(503, "支付功能暂未开放");
        }
    }

    @Transactional
    public PayOrderResponse createOrder(Long userId, CreatePayOrderRequest req, String clientIp) {
        requirePayEnabled();

        // 行锁串行化同用户下单，避免并发突破开单上限/双开未完成单
        SysUserEntity user = sysUserMapper.selectByIdForUpdate(userId);
        if (user == null) {
            throw new BusinessException(401, "用户不存在");
        }
        if (UserRole.SUPER_ADMIN.name().equals(user.getRole())) {
            throw new BusinessException(400, "超级管理员无需购买会员");
        }

        String channel = PayChannel.normalize(req.getChannel());
        String clientType = StringUtils.hasText(req.getClientType())
                ? req.getClientType().trim().toUpperCase()
                : "PC";

        Long planId;
        try {
            planId = Long.parseLong(req.getPlanId().trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "planId 无效");
        }
        MemberPlanEntity plan = memberPlanService.requireOnSale(planId);

        LocalDateTime now = LocalDateTime.now(MemberStatusService.ZONE);

        // KD16: 同 user+plan+channel 未终态未过期 → 幂等返回
        PayOrderEntity existing = payOrderMapper.selectOne(
                new LambdaQueryWrapper<PayOrderEntity>()
                        .eq(PayOrderEntity::getUserId, userId)
                        .eq(PayOrderEntity::getPlanId, planId)
                        .eq(PayOrderEntity::getChannel, channel)
                        .in(PayOrderEntity::getStatus, PayOrderStatus.CREATED, PayOrderStatus.PAYING)
                        .gt(PayOrderEntity::getExpireAt, now)
                        .orderByDesc(PayOrderEntity::getCreatedAt)
                        .last("LIMIT 1")
        );
        if (existing != null) {
            log.info("幂等复用支付订单 orderNo={} userId={}", existing.getOrderNo(), userId);
            return toResponse(existing, resolvePayMode(existing), true);
        }

        long openCount = payOrderMapper.selectCount(
                new LambdaQueryWrapper<PayOrderEntity>()
                        .eq(PayOrderEntity::getUserId, userId)
                        .in(PayOrderEntity::getStatus, PayOrderStatus.CREATED, PayOrderStatus.PAYING)
                        .gt(PayOrderEntity::getExpireAt, now)
        );
        if (openCount >= payProperties.getMaxOpenOrdersPerUser()) {
            throw new BusinessException(400, "您有未完成的支付订单，请先完成或等待超时");
        }

        PaymentChannel paymentChannel = channelRegistry.require(channel);

        PayOrderEntity order = new PayOrderEntity();
        order.setOrderNo(OrderNoGenerator.next());
        order.setUserId(userId);
        order.setPlanId(plan.getId());
        order.setPlanCode(plan.getCode());
        order.setPlanName(plan.getName());
        order.setDurationDays(plan.getDurationDays());
        order.setChannel(channel);
        order.setClientType(clientType);
        order.setAmountCents(plan.getPriceCents());
        order.setCurrency(plan.getCurrency() != null ? plan.getCurrency() : "CNY");
        order.setStatus(PayOrderStatus.CREATED);
        order.setFulfilled(0);
        order.setClientIp(clientIp);
        order.setExpireAt(now.plusMinutes(payProperties.getOrderExpireMinutes()));
        order.setVersion(0);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        payOrderMapper.insert(order);

        String base = trimSlash(payProperties.getPublicBaseUrl());
        PayCreateContext ctx = PayCreateContext.builder()
                .clientType(clientType)
                .clientIp(clientIp)
                .notifyAbsoluteUrl(base + notifyPath(channel))
                .returnAbsoluteUrl(base + returnPath(channel))
                .build();

        try {
            PaymentCreateResult created = paymentChannel.createPayment(order, ctx);
            int n = payOrderMapper.casToPaying(
                    order.getOrderNo(),
                    created.getCodeUrl(),
                    created.getPayUrl(),
                    created.getPrepayId(),
                    created.getChannelExtraJson()
            );
            if (n == 0) {
                throw new BusinessException(500, "更新订单为 PAYING 失败");
            }
            order = payOrderMapper.selectOne(
                    new LambdaQueryWrapper<PayOrderEntity>().eq(PayOrderEntity::getOrderNo, order.getOrderNo())
            );
            log.info("创建支付订单成功 orderNo={} userId={} channel={} amountCents={}",
                    order.getOrderNo(), userId, channel, order.getAmountCents());
            return toResponse(order, created.getPayMode(), false);
        } catch (BusinessException e) {
            payOrderMapper.casToFailed(order.getOrderNo(), truncate(e.getMessage(), 60));
            throw e;
        } catch (Exception e) {
            log.error("渠道下单失败 orderNo={}", order.getOrderNo(), e);
            payOrderMapper.casToFailed(order.getOrderNo(), "channel_create_failed");
            throw new BusinessException(500, "渠道下单失败: " + e.getMessage());
        }
    }

    public PayOrderResponse getMyOrder(Long userId, String orderNo) {
        PayOrderEntity order = requireOwned(userId, orderNo);
        return toResponse(order, resolvePayMode(order), null);
    }

    public List<PayOrderResponse> listMyOrders(Long userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        Page<PayOrderEntity> mp = new Page<>(safePage + 1L, safeSize);
        Page<PayOrderEntity> result = payOrderMapper.selectPage(mp,
                new LambdaQueryWrapper<PayOrderEntity>()
                        .eq(PayOrderEntity::getUserId, userId)
                        .orderByDesc(PayOrderEntity::getCreatedAt));
        return result.getRecords().stream()
                .map(o -> toResponse(o, resolvePayMode(o), null))
                .collect(Collectors.toList());
    }

    @Transactional
    public PayOrderResponse cancelMyOrder(Long userId, String orderNo) {
        PayOrderEntity order = requireOwned(userId, orderNo);
        if (!PayOrderStatus.isOpen(order.getStatus())) {
            throw new BusinessException(400, "订单已结束，无法取消");
        }
        int n = payOrderMapper.casToClosed(orderNo, "user_cancel");
        if (n == 0) {
            throw new BusinessException(400, "取消失败，订单状态已变更");
        }
        return getMyOrder(userId, orderNo);
    }

    /**
     * Mock 确认支付：登录 + mock-enabled。
     */
    @Transactional
    public PayOrderResponse mockConfirm(Long userId, String orderNo) {
        requirePayEnabled();
        if (!payProperties.isMockEnabled()) {
            throw new BusinessException(400, "Mock 支付未开启");
        }
        PayOrderEntity order = requireOwned(userId, orderNo);
        if (!PayChannel.MOCK.equals(order.getChannel())) {
            throw new BusinessException(400, "非 Mock 订单");
        }
        if (PayOrderStatus.SUCCESS.equals(order.getStatus()) && order.getFulfilled() != null && order.getFulfilled() == 1) {
            return toResponse(order, "MOCK", null);
        }
        if (PayOrderStatus.CLOSED.equals(order.getStatus()) || PayOrderStatus.FAILED.equals(order.getStatus())) {
            // 允许 CLOSED 补单复活；FAILED 不允许
            if (PayOrderStatus.FAILED.equals(order.getStatus())) {
                throw new BusinessException(400, "订单已失败，请重新下单");
            }
        }
        String tradeNo = "MOCK-TX-" + order.getOrderNo();
        payFulfillService.markSuccessAndFulfill(order.getOrderNo(), tradeNo, order.getAmountCents().longValue());
        return getMyOrder(userId, orderNo);
    }

    public int closeExpiredOrders() {
        LocalDateTime now = LocalDateTime.now(MemberStatusService.ZONE);
        List<PayOrderEntity> list = payOrderMapper.selectList(
                new LambdaQueryWrapper<PayOrderEntity>()
                        .in(PayOrderEntity::getStatus, PayOrderStatus.CREATED, PayOrderStatus.PAYING)
                        .lt(PayOrderEntity::getExpireAt, now)
                        .last("LIMIT 200")
        );
        int closed = 0;
        for (PayOrderEntity o : list) {
            closed += payOrderMapper.casToClosed(o.getOrderNo(), "timeout");
        }
        if (closed > 0) {
            log.info("closeExpiredOrders closed={}", closed);
        }
        return closed;
    }

    /**
     * 查单补单：扫描 PAYING，渠道已付则履约。Mock 默认不查成功。
     * 每单独立事务（经 PayFulfillService）。
     */
    public int reconcilePayingOrders() {
        List<PayOrderEntity> list = payOrderMapper.selectList(
                new LambdaQueryWrapper<PayOrderEntity>()
                        .eq(PayOrderEntity::getStatus, PayOrderStatus.PAYING)
                        .last("LIMIT 100")
        );
        int fixed = 0;
        for (PayOrderEntity o : list) {
            try {
                PaymentChannel ch = channelRegistry.require(o.getChannel());
                ChannelTradeQueryResult q = ch.queryPayment(o);
                if (q != null && q.isPaid()) {
                    payFulfillService.markSuccessAndFulfill(o.getOrderNo(), q.getTradeNo(), q.getAmountCents());
                    fixed++;
                }
            } catch (Exception e) {
                log.warn("reconcile failed orderNo={}: {}", o.getOrderNo(), e.getMessage());
            }
        }
        return fixed;
    }

    /**
     * SUCCESS + fulfilled=0 补偿；每单独立事务。
     */
    public int fulfillPending() {
        LocalDateTime threshold = LocalDateTime.now(MemberStatusService.ZONE)
                .minusSeconds(payProperties.getFulfillPendingGraceSeconds());
        List<PayOrderEntity> list = payOrderMapper.selectList(
                new LambdaQueryWrapper<PayOrderEntity>()
                        .eq(PayOrderEntity::getStatus, PayOrderStatus.SUCCESS)
                        .eq(PayOrderEntity::getFulfilled, 0)
                        .lt(PayOrderEntity::getUpdatedAt, threshold)
                        .last("LIMIT 100")
        );
        int n = 0;
        for (PayOrderEntity o : list) {
            try {
                if (payFulfillService.recoverUnfulfilled(o.getOrderNo())) {
                    n++;
                }
            } catch (Exception e) {
                log.error("fulfillPending error orderNo={}: {}", o.getOrderNo(), e.getMessage(), e);
            }
        }
        return n;
    }

    private PayOrderEntity requireOwned(Long userId, String orderNo) {
        if (!StringUtils.hasText(orderNo)) {
            throw new BusinessException(400, "orderNo 不能为空");
        }
        PayOrderEntity order = payOrderMapper.selectOne(
                new LambdaQueryWrapper<PayOrderEntity>().eq(PayOrderEntity::getOrderNo, orderNo.trim())
        );
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(404, "订单不存在");
        }
        return order;
    }

    private PayOrderResponse toResponse(PayOrderEntity o, String payMode, Boolean idempotentReuse) {
        return PayOrderResponse.builder()
                .orderNo(o.getOrderNo())
                .channel(o.getChannel())
                .status(o.getStatus())
                .amountCents(o.getAmountCents())
                .amountYuan(o.getAmountCents() == null ? null : MoneyUtil.centsToYuan(o.getAmountCents()))
                .planId(o.getPlanId() == null ? null : String.valueOf(o.getPlanId()))
                .planCode(o.getPlanCode())
                .planName(o.getPlanName())
                .durationDays(o.getDurationDays())
                .payMode(payMode)
                .qrCodeUrl(o.getCodeUrl())
                .payUrl(o.getPayUrl())
                .fulfilled(o.getFulfilled())
                .tradeNo(o.getTradeNo())
                .expireAt(o.getExpireAt())
                .paidAt(o.getPaidAt())
                .createdAt(o.getCreatedAt())
                .idempotentReuse(idempotentReuse)
                .build();
    }

    private String resolvePayMode(PayOrderEntity o) {
        if (PayChannel.MOCK.equals(o.getChannel())) {
            return "MOCK";
        }
        if (StringUtils.hasText(o.getCodeUrl())) {
            return "NATIVE_QR";
        }
        if (StringUtils.hasText(o.getPayUrl())) {
            return "H5_URL";
        }
        return null;
    }

    private String notifyPath(String channel) {
        if (PayChannel.ALIPAY.equals(channel)) {
            return payProperties.getAlipay().getNotifyPath();
        }
        if (PayChannel.WECHAT.equals(channel)) {
            return payProperties.getWechat().getNotifyPath();
        }
        return "/api/pay/notify/" + channel;
    }

    private String returnPath(String channel) {
        if (PayChannel.ALIPAY.equals(channel)) {
            return payProperties.getAlipay().getReturnPath();
        }
        return "/api/pay/return/" + channel;
    }

    private static String trimSlash(String base) {
        if (base == null || base.isBlank()) {
            return "";
        }
        String b = base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
