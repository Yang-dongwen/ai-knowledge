package com.dwcode.okxbot.pay.channel.stripe;

import com.dwcode.okxbot.auth.config.AuthProperties;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.member.service.MemberStatusService;
import com.dwcode.okxbot.pay.channel.ChannelTradeQueryResult;
import com.dwcode.okxbot.pay.channel.NotifyParseResult;
import com.dwcode.okxbot.pay.channel.PayCreateContext;
import com.dwcode.okxbot.pay.channel.PaymentChannel;
import com.dwcode.okxbot.pay.channel.PaymentCreateResult;
import com.dwcode.okxbot.pay.config.PayProperties;
import com.dwcode.okxbot.pay.entity.PayOrderEntity;
import com.dwcode.okxbot.pay.enums.PayChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * Stripe Checkout 托管页：创建 Session 后返回 {@code url} 供跳转。
 * <p>履约以 Webhook {@code checkout.session.completed}（payment_status=paid）为准，
 * 查单走 {@code Session.retrieve}。官方文档：
 * <a href="https://docs.stripe.com/checkout/quickstart">Checkout</a>、
 * <a href="https://docs.stripe.com/webhooks">Webhooks</a>。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StripePaymentChannel implements PaymentChannel {

    private static final Set<String> PAID_EVENTS = Set.of(
            "checkout.session.completed",
            "checkout.session.async_payment_succeeded"
    );

    private final PayProperties payProperties;
    private final AuthProperties authProperties;
    private final StripeClientFactory clientFactory;
    private final ObjectMapper objectMapper;

    @Override
    public String channelId() {
        return PayChannel.STRIPE;
    }

    @Override
    public PaymentCreateResult createPayment(PayOrderEntity order, PayCreateContext ctx) {
        clientFactory.requireReady();
        PayProperties.Stripe conf = payProperties.getStripe();
        try {
            String subject = conf.getSubjectPrefix()
                    + (order.getPlanName() != null ? order.getPlanName() : "会员");
            String currency = order.getCurrency() == null ? "cny" : order.getCurrency().trim().toLowerCase();
            long unitAmount = order.getAmountCents() == null ? 0L : order.getAmountCents().longValue();
            if (unitAmount <= 0) {
                throw new BusinessException(400, "Stripe 下单金额无效");
            }

            String successUrl = resolveCustomerReturnUrl(order, ctx, false);
            String cancelUrl = resolveCustomerReturnUrl(order, ctx, true);

            SessionCreateParams.Builder builder = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(cancelUrl)
                    .setClientReferenceId(order.getOrderNo())
                    .setExpiresAt(checkoutExpiresAtEpoch(order))
                    .putMetadata("order_no", order.getOrderNo())
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                    .setCurrency(currency)
                                    .setUnitAmount(unitAmount)
                                    .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                            .setName(subject)
                                            .build())
                                    .build())
                            .build())
                    .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                            .putMetadata("order_no", order.getOrderNo())
                            .build());

            Session session = Session.create(builder.build(), clientFactory.requestOptions());
            if (session == null || !StringUtils.hasText(session.getUrl())) {
                throw new BusinessException(502, "Stripe 下单无支付链接");
            }
            log.info("stripe checkout session ok orderNo={} sessionId={} sandbox={}",
                    order.getOrderNo(), session.getId(), conf.isSandbox());
            String extra = "{\"api\":\"checkout.sessions\",\"sandbox\":"
                    + conf.isSandbox()
                    + ",\"sessionId\":\"" + session.getId() + "\"}";
            return PaymentCreateResult.builder()
                    .payMode("H5_URL")
                    .codeUrl(null)
                    .payUrl(session.getUrl())
                    .prepayId(session.getId())
                    .channelExtraJson(extra)
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (StripeException e) {
            log.error("stripe checkout api error orderNo={} code={} msg={}",
                    order.getOrderNo(), e.getCode(), e.getMessage());
            String msg = e.getUserMessage() != null ? e.getUserMessage() : e.getMessage();
            throw new BusinessException(502, "Stripe 下单失败: " + msg);
        } catch (Exception e) {
            log.error("stripe checkout error orderNo={}", order.getOrderNo(), e);
            throw new BusinessException(502, "Stripe 下单异常: " + e.getMessage());
        }
    }

    @Override
    public NotifyParseResult parseAndVerifyNotify(HttpHeaders headers, String rawBody) {
        PayProperties.Stripe conf = payProperties.getStripe();
        if (!StringUtils.hasText(conf.getWebhookSecret())) {
            return NotifyParseResult.builder()
                    .signatureValid(false)
                    .paid(false)
                    .rawTradeState("WEBHOOK_SECRET_MISSING")
                    .build();
        }
        if (!StringUtils.hasText(rawBody)) {
            return NotifyParseResult.builder()
                    .signatureValid(false)
                    .paid(false)
                    .rawTradeState("EMPTY_BODY")
                    .build();
        }
        String sig = headers == null ? null : headers.getFirst("Stripe-Signature");
        if (!StringUtils.hasText(sig)) {
            return NotifyParseResult.builder()
                    .signatureValid(false)
                    .paid(false)
                    .rawTradeState("NO_SIGNATURE")
                    .build();
        }
        try {
            // 只用官方 HMAC 验签，事件体用 Jackson 解析，避免 API 版本导致 Event 反序列化失败
            Webhook.Signature.verifyHeader(rawBody, sig, conf.getWebhookSecret().trim(), 300);
        } catch (SignatureVerificationException e) {
            log.warn("stripe webhook signature fail: {}", e.getMessage());
            return NotifyParseResult.builder()
                    .signatureValid(false)
                    .paid(false)
                    .rawTradeState("SIGN_FAIL")
                    .build();
        } catch (Exception e) {
            log.warn("stripe webhook construct fail: {}", e.getMessage());
            return NotifyParseResult.builder()
                    .signatureValid(false)
                    .paid(false)
                    .rawTradeState("CONSTRUCT_FAIL")
                    .build();
        }
        return parseEventPayload(rawBody);
    }

    NotifyParseResult parseEventPayload(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String type = text(root, "type");
            JsonNode obj = root.path("data").path("object");
            String orderNo = firstNonBlank(
                    text(obj, "client_reference_id"),
                    text(obj.path("metadata"), "order_no")
            );
            String sessionId = text(obj, "id");
            String paymentIntent = asId(obj.get("payment_intent"));
            String paymentStatus = text(obj, "payment_status");
            long amountCents = obj.path("amount_total").asLong(0L);
            boolean paidEvent = type != null && PAID_EVENTS.contains(type);
            boolean paid = paidEvent && "paid".equalsIgnoreCase(paymentStatus);
            if ("checkout.session.async_payment_succeeded".equals(type)) {
                paid = true;
            }
            return NotifyParseResult.builder()
                    .signatureValid(true)
                    .paid(paid)
                    .orderNo(orderNo)
                    .tradeNo(StringUtils.hasText(paymentIntent) ? paymentIntent : sessionId)
                    .amountCents(amountCents)
                    .appIdOrMchIdHint(sessionId)
                    .rawTradeState(type + "|" + paymentStatus)
                    .build();
        } catch (Exception e) {
            log.warn("stripe webhook json parse fail: {}", e.getMessage());
            return NotifyParseResult.builder()
                    .signatureValid(true)
                    .paid(false)
                    .rawTradeState("JSON_PARSE_FAIL")
                    .build();
        }
    }

    @Override
    public ChannelTradeQueryResult queryPayment(PayOrderEntity order) {
        if (!clientFactory.isReady()) {
            return ChannelTradeQueryResult.builder()
                    .paid(false)
                    .rawTradeState("NOT_CONFIGURED")
                    .amountCents(order.getAmountCents() == null ? 0 : order.getAmountCents())
                    .build();
        }
        if (!StringUtils.hasText(order.getPrepayId())) {
            return ChannelTradeQueryResult.builder()
                    .paid(false)
                    .rawTradeState("NO_SESSION")
                    .amountCents(order.getAmountCents() == null ? 0 : order.getAmountCents())
                    .build();
        }
        try {
            Session session = Session.retrieve(order.getPrepayId().trim(), clientFactory.requestOptions());
            boolean paid = session != null && "paid".equalsIgnoreCase(session.getPaymentStatus());
            // 履约按本单金额：Session 是我们创建的，payment_status=paid 即已付该套餐
            long cents = order.getAmountCents() == null ? 0 : order.getAmountCents();
            if (paid && session.getAmountTotal() != null
                    && session.getAmountTotal().longValue() != cents) {
                log.warn("stripe amount_total={} orderCents={} orderNo={} sessionId={}",
                        session.getAmountTotal(), cents, order.getOrderNo(), session.getId());
            }
            String tradeNo = session == null ? null : session.getPaymentIntent();
            if (!StringUtils.hasText(tradeNo) && session != null) {
                tradeNo = session.getId();
            }
            return ChannelTradeQueryResult.builder()
                    .paid(paid)
                    .tradeNo(tradeNo)
                    .amountCents(cents)
                    .rawTradeState(session == null ? "NULL"
                            : session.getStatus() + "|" + session.getPaymentStatus())
                    .build();
        } catch (Exception e) {
            log.warn("stripe query fail orderNo={}: {}", order.getOrderNo(), e.getMessage());
            return ChannelTradeQueryResult.builder()
                    .paid(false)
                    .rawTradeState("QUERY_ERROR")
                    .amountCents(order.getAmountCents() == null ? 0 : order.getAmountCents())
                    .build();
        }
    }

    /**
     * Checkout Session expires_at：官方允许创建后 30 分钟～24 小时。
     */
    static long checkoutExpiresAtEpoch(PayOrderEntity order) {
        Instant now = Instant.now();
        Instant min = now.plus(30, ChronoUnit.MINUTES);
        Instant max = now.plus(24, ChronoUnit.HOURS);
        Instant exp = min;
        if (order.getExpireAt() != null) {
            exp = order.getExpireAt().atZone(MemberStatusService.ZONE).toInstant();
        }
        if (exp.isBefore(min)) {
            exp = min;
        }
        if (exp.isAfter(max)) {
            exp = max;
        }
        return exp.getEpochSecond();
    }

    private String resolveCustomerReturnUrl(PayOrderEntity order, PayCreateContext ctx, boolean canceled) {
        PayProperties.Stripe conf = payProperties.getStripe();
        String frontend = authProperties.getOauth() == null
                ? null
                : authProperties.getOauth().getFrontendBaseUrl();
        String path = conf.getFrontendReturnPath();
        if (StringUtils.hasText(frontend) && StringUtils.hasText(path)) {
            StringBuilder sb = new StringBuilder(trimSlash(frontend))
                    .append(path.startsWith("/") ? path : "/" + path)
                    .append("?orderNo=")
                    .append(order.getOrderNo());
            if (canceled) {
                sb.append("&canceled=1");
            }
            return sb.toString();
        }
        return ctx.getReturnAbsoluteUrl();
    }

    private static String trimSlash(String base) {
        if (base == null) {
            return "";
        }
        String b = base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    private static String text(JsonNode n, String field) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return StringUtils.hasText(s) ? s : null;
    }

    private static String asId(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isObject()) {
            return text(n, "id");
        }
        return n.asText(null);
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StringUtils.hasText(v)) {
                return v;
            }
        }
        return null;
    }
}
