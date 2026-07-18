package com.dwcode.okxbot.pay.service;

import com.dwcode.okxbot.pay.channel.NotifyParseResult;
import com.dwcode.okxbot.pay.channel.PaymentChannel;
import com.dwcode.okxbot.pay.channel.PaymentChannelRegistry;
import com.dwcode.okxbot.pay.entity.PayNotifyLogEntity;
import com.dwcode.okxbot.pay.enums.PayChannel;
import com.dwcode.okxbot.pay.mapper.PayNotifyLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 异步通知处理：验签 → 交易态 → 金额 → 履约。
 * 调用方负责 catch 并返回渠道协议 body，勿抛到 GlobalExceptionHandler。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayNotifyService {

    private final PaymentChannelRegistry channelRegistry;
    private final PayFulfillService payFulfillService;
    private final PayNotifyLogMapper payNotifyLogMapper;

    /**
     * @return true 表示应向渠道 ACK success；false 表示 fail（促重试）
     */
    public boolean handle(String channelId, HttpHeaders headers, String rawBody) {
        String ch = channelId == null ? "" : channelId.trim().toLowerCase();
        PaymentChannel channel;
        try {
            // notify 时即使 enabled=false 也应能验签？关闭时拒绝
            channel = channelRegistry.require(ch);
        } catch (Exception e) {
            log.warn("notify channel unavailable: {}", e.getMessage());
            saveLog(ch, null, rawBody, headers, false, "CHANNEL_OFF", e.getMessage());
            return false;
        }

        NotifyParseResult parsed;
        try {
            parsed = channel.parseAndVerifyNotify(headers, rawBody == null ? "" : rawBody);
        } catch (Exception e) {
            log.error("notify parse error channel={}", ch, e);
            saveLog(ch, null, rawBody, headers, false, "PARSE_ERROR", e.getMessage());
            return false;
        }

        if (parsed == null || !parsed.isSignatureValid()) {
            saveLog(ch, parsed == null ? null : parsed.getOrderNo(), rawBody, headers,
                    false, "SIGN_FAIL", parsed == null ? null : parsed.getRawTradeState());
            return false;
        }

        if (!parsed.isPaid()) {
            // 验签通过但非成功态：ACK，避免无意义重试
            saveLog(ch, parsed.getOrderNo(), rawBody, headers, true, "IGNORED",
                    parsed.getRawTradeState());
            log.info("notify ignored (not paid) channel={} orderNo={} state={}",
                    ch, parsed.getOrderNo(), parsed.getRawTradeState());
            return true;
        }

        if (!StringUtils.hasText(parsed.getOrderNo())) {
            saveLog(ch, null, rawBody, headers, true, "NO_ORDER_NO", null);
            return false;
        }

        try {
            payFulfillService.markSuccessAndFulfill(
                    parsed.getOrderNo(),
                    parsed.getTradeNo(),
                    parsed.getAmountCents()
            );
            saveLog(ch, parsed.getOrderNo(), rawBody, headers, true, "SUCCESS",
                    parsed.getRawTradeState());
            log.info("notify fulfilled channel={} orderNo={} tradeNo={}",
                    ch, parsed.getOrderNo(), parsed.getTradeNo());
            return true;
        } catch (Exception e) {
            log.error("notify fulfill fail channel={} orderNo={}: {}",
                    ch, parsed.getOrderNo(), e.getMessage(), e);
            saveLog(ch, parsed.getOrderNo(), rawBody, headers, true, "FULFILL_FAIL",
                    truncate(e.getMessage(), 200));
            // 金额错误等业务问题：ACK 避免死循环？金额错误应 fail 告警。
            // 设计：金额不匹配等 → fail 以便运营感知；已 SUCCESS 幂等不会进这里。
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("金额") || msg.contains("不存在")) {
                return false;
            }
            // 临时故障 → fail 促重试
            return false;
        }
    }

    private void saveLog(String channel, String orderNo, String rawBody, HttpHeaders headers,
                         boolean verifyOk, String result, String err) {
        try {
            PayNotifyLogEntity logEntity = new PayNotifyLogEntity();
            logEntity.setChannel(channel);
            logEntity.setOrderNo(orderNo);
            logEntity.setBodyRaw(truncate(rawBody, 8000));
            logEntity.setHeadersJson(headers == null ? null : truncate(headers.toString(), 2000));
            logEntity.setVerifyOk(verifyOk ? 1 : 0);
            logEntity.setProcessResult(result);
            logEntity.setErrorMessage(truncate(err, 500));
            logEntity.setCreatedAt(LocalDateTime.now());
            payNotifyLogMapper.insert(logEntity);
        } catch (Exception e) {
            log.warn("save pay_notify_log failed: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
