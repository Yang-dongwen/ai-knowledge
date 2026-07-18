package com.dwcode.okxbot.pay.channel.alipay;

import com.alipay.api.AlipayApiException;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.pay.channel.ChannelTradeQueryResult;
import com.dwcode.okxbot.pay.channel.NotifyParseResult;
import com.dwcode.okxbot.pay.channel.PayCreateContext;
import com.dwcode.okxbot.pay.channel.PaymentChannel;
import com.dwcode.okxbot.pay.channel.PaymentCreateResult;
import com.dwcode.okxbot.pay.config.PayProperties;
import com.dwcode.okxbot.pay.entity.PayOrderEntity;
import com.dwcode.okxbot.pay.enums.PayChannel;
import com.dwcode.okxbot.pay.util.MoneyUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 支付宝直连：PC 当面付 precreate 扫码；H5 手机网站 wap.pay。
 * <p>无资质时 pay.alipay.enabled=false，本 Bean 仍注册但不被选用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlipayPaymentChannel implements PaymentChannel {

    private static final Set<String> SUCCESS_STATES = Set.of("TRADE_SUCCESS", "TRADE_FINISHED");

    private final PayProperties payProperties;
    private final AlipayClientFactory clientFactory;
    private final ObjectMapper objectMapper;

    @Override
    public String channelId() {
        return PayChannel.ALIPAY;
    }

    @Override
    public PaymentCreateResult createPayment(PayOrderEntity order, PayCreateContext ctx) {
        clientFactory.requireReady();
        String clientType = ctx.getClientType() == null ? "PC" : ctx.getClientType().toUpperCase();
        if ("H5".equals(clientType) || "WAP".equals(clientType) || "MOBILE".equals(clientType)) {
            return createWapPay(order, ctx);
        }
        return createPrecreate(order, ctx);
    }

    private PaymentCreateResult createPrecreate(PayOrderEntity order, PayCreateContext ctx) {
        try {
            AlipayTradePrecreateRequest request = new AlipayTradePrecreateRequest();
            if (StringUtils.hasText(ctx.getNotifyAbsoluteUrl())) {
                request.setNotifyUrl(ctx.getNotifyAbsoluteUrl());
            }
            request.setBizContent(buildBizContent(order));
            AlipayTradePrecreateResponse resp = clientFactory.getClient().execute(request);
            if (resp == null || !resp.isSuccess()) {
                String msg = resp == null ? "null response" : resp.getSubMsg() + " / " + resp.getMsg();
                log.error("alipay precreate fail orderNo={} msg={}", order.getOrderNo(), msg);
                throw new BusinessException(502, "支付宝下单失败: " + msg);
            }
            log.info("alipay precreate ok orderNo={} codeUrlPresent={}",
                    order.getOrderNo(), StringUtils.hasText(resp.getQrCode()));
            return PaymentCreateResult.builder()
                    .payMode("NATIVE_QR")
                    .codeUrl(resp.getQrCode())
                    .payUrl(null)
                    .prepayId(resp.getOutTradeNo())
                    .channelExtraJson("{\"api\":\"alipay.trade.precreate\"}")
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (AlipayApiException e) {
            log.error("alipay precreate api error orderNo={}", order.getOrderNo(), e);
            throw new BusinessException(502, "支付宝接口异常: " + e.getErrMsg());
        } catch (Exception e) {
            log.error("alipay precreate error orderNo={}", order.getOrderNo(), e);
            throw new BusinessException(502, "支付宝下单异常: " + e.getMessage());
        }
    }

    /**
     * 手机网站支付：pageExecute GET 得到跳转 URL（部分环境返回 form HTML，仍放入 payUrl/extra）。
     */
    private PaymentCreateResult createWapPay(PayOrderEntity order, PayCreateContext ctx) {
        try {
            AlipayTradeWapPayRequest request = new AlipayTradeWapPayRequest();
            if (StringUtils.hasText(ctx.getNotifyAbsoluteUrl())) {
                request.setNotifyUrl(ctx.getNotifyAbsoluteUrl());
            }
            if (StringUtils.hasText(ctx.getReturnAbsoluteUrl())) {
                request.setReturnUrl(ctx.getReturnAbsoluteUrl());
            }
            request.setBizContent(buildBizContent(order));
            var resp = clientFactory.getClient().pageExecute(request, "GET");
            String body = resp == null ? null : resp.getBody();
            if (!StringUtils.hasText(body)) {
                throw new BusinessException(502, "支付宝 H5 下单无返回");
            }
            // GET 模式通常直接是 gateway URL
            boolean isUrl = body.startsWith("http://") || body.startsWith("https://");
            return PaymentCreateResult.builder()
                    .payMode(isUrl ? "H5_URL" : "H5_FORM")
                    .codeUrl(null)
                    .payUrl(isUrl ? body : null)
                    .prepayId(order.getOrderNo())
                    .channelExtraJson(isUrl
                            ? "{\"api\":\"alipay.trade.wap.pay\",\"mode\":\"GET\"}"
                            : "{\"api\":\"alipay.trade.wap.pay\",\"mode\":\"FORM\",\"formHtml\":true}")
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (AlipayApiException e) {
            log.error("alipay wap api error orderNo={}", order.getOrderNo(), e);
            throw new BusinessException(502, "支付宝 H5 接口异常: " + e.getErrMsg());
        } catch (Exception e) {
            log.error("alipay wap error orderNo={}", order.getOrderNo(), e);
            throw new BusinessException(502, "支付宝 H5 下单异常: " + e.getMessage());
        }
    }

    private String buildBizContent(PayOrderEntity order) {
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("out_trade_no", order.getOrderNo());
            n.put("total_amount", MoneyUtil.centsToYuan(order.getAmountCents()));
            String subject = payProperties.getAlipay().getSubjectPrefix()
                    + (order.getPlanName() != null ? order.getPlanName() : "会员");
            n.put("subject", subject);
            // 当面付/手机网站通用
            n.put("product_code", "H5".equalsIgnoreCase(order.getClientType())
                    || "WAP".equalsIgnoreCase(order.getClientType())
                    ? "QUICK_WAP_WAY"
                    : "FACE_TO_FACE_PAYMENT");
            if (order.getExpireAt() != null) {
                // yyyy-MM-dd HH:mm:ss
                n.put("timeout_express", Math.max(1, payProperties.getOrderExpireMinutes()) + "m");
            }
            return objectMapper.writeValueAsString(n);
        } catch (Exception e) {
            throw new BusinessException(500, "构造支付宝业务参数失败");
        }
    }

    @Override
    public NotifyParseResult parseAndVerifyNotify(HttpHeaders headers, String rawBody) {
        PayProperties.Alipay conf = payProperties.getAlipay();
        Map<String, String> params = parseFormBody(rawBody);
        if (params.isEmpty()) {
            return NotifyParseResult.builder()
                    .signatureValid(false)
                    .paid(false)
                    .rawTradeState("EMPTY_BODY")
                    .build();
        }
        boolean signOk = false;
        try {
            signOk = AlipaySignature.rsaCheckV1(
                    params,
                    AlipayClientFactory.normalizeKey(conf.getAlipayPublicKey()),
                    conf.getCharset(),
                    conf.getSignType()
            );
        } catch (Exception e) {
            log.warn("alipay notify verify error: {}", e.getMessage());
        }
        if (!signOk) {
            return NotifyParseResult.builder()
                    .signatureValid(false)
                    .paid(false)
                    .orderNo(params.get("out_trade_no"))
                    .rawTradeState("SIGN_FAIL")
                    .build();
        }

        String tradeStatus = params.get("trade_status");
        String outTradeNo = params.get("out_trade_no");
        String tradeNo = params.get("trade_no");
        String totalAmount = params.get("total_amount");
        long amountCents = 0L;
        try {
            if (StringUtils.hasText(totalAmount)) {
                amountCents = MoneyUtil.yuanStringToCents(totalAmount);
            }
        } catch (BusinessException e) {
            log.warn("alipay notify amount parse fail: {}", totalAmount);
            return NotifyParseResult.builder()
                    .signatureValid(true)
                    .paid(false)
                    .orderNo(outTradeNo)
                    .tradeNo(tradeNo)
                    .rawTradeState(tradeStatus + "|AMOUNT_PARSE_FAIL")
                    .build();
        }

        boolean paid = tradeStatus != null && SUCCESS_STATES.contains(tradeStatus);
        // app_id 校验
        String appId = params.get("app_id");
        if (StringUtils.hasText(conf.getAppId()) && StringUtils.hasText(appId)
                && !conf.getAppId().trim().equals(appId.trim())) {
            log.warn("alipay notify app_id mismatch expect={} got={}", conf.getAppId(), appId);
            paid = false;
        }

        return NotifyParseResult.builder()
                .signatureValid(true)
                .paid(paid)
                .orderNo(outTradeNo)
                .tradeNo(tradeNo)
                .amountCents(amountCents)
                .appIdOrMchIdHint(appId)
                .rawTradeState(tradeStatus)
                .build();
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
        try {
            AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
            ObjectNode n = objectMapper.createObjectNode();
            n.put("out_trade_no", order.getOrderNo());
            request.setBizContent(objectMapper.writeValueAsString(n));
            AlipayTradeQueryResponse resp = clientFactory.getClient().execute(request);
            if (resp == null || !resp.isSuccess()) {
                String st = resp == null ? "NULL" : resp.getSubCode() + ":" + resp.getSubMsg();
                return ChannelTradeQueryResult.builder()
                        .paid(false)
                        .rawTradeState(st)
                        .amountCents(order.getAmountCents() == null ? 0 : order.getAmountCents())
                        .build();
            }
            String tradeStatus = resp.getTradeStatus();
            boolean paid = tradeStatus != null && SUCCESS_STATES.contains(tradeStatus);
            long cents = order.getAmountCents() == null ? 0 : order.getAmountCents();
            if (StringUtils.hasText(resp.getTotalAmount())) {
                try {
                    cents = MoneyUtil.yuanStringToCents(resp.getTotalAmount());
                } catch (Exception ignored) {
                    // keep order amount
                }
            }
            return ChannelTradeQueryResult.builder()
                    .paid(paid)
                    .tradeNo(resp.getTradeNo())
                    .amountCents(cents)
                    .rawTradeState(tradeStatus)
                    .build();
        } catch (Exception e) {
            log.warn("alipay query fail orderNo={}: {}", order.getOrderNo(), e.getMessage());
            return ChannelTradeQueryResult.builder()
                    .paid(false)
                    .rawTradeState("QUERY_ERROR")
                    .amountCents(order.getAmountCents() == null ? 0 : order.getAmountCents())
                    .build();
        }
    }

    /**
     * application/x-www-form-urlencoded body → map（支付宝异步通知）。
     */
    static Map<String, String> parseFormBody(String rawBody) {
        Map<String, String> map = new HashMap<>();
        if (!StringUtils.hasText(rawBody)) {
            return map;
        }
        String[] pairs = rawBody.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = urlDecode(pair.substring(0, idx));
            String val = urlDecode(pair.substring(idx + 1));
            map.put(key, val);
        }
        return map;
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
