package com.dwcode.okxbot.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.auth.config.AuthProperties;
import com.dwcode.okxbot.auth.entity.EmailCodeEntity;
import com.dwcode.okxbot.auth.enums.EmailCodePurpose;
import com.dwcode.okxbot.auth.mapper.EmailCodeMapper;
import com.dwcode.okxbot.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailCodeService {

    private final EmailCodeMapper emailCodeMapper;
    private final AuthProperties authProperties;
    private final MailService mailService;

    private final SecureRandom random = new SecureRandom();
    /** email|purpose -> last send epoch ms */
    private final Map<String, Long> lastSendAt = new ConcurrentHashMap<>();
    /** email|purpose -> fail count window */
    private final Map<String, FailBucket> failBuckets = new ConcurrentHashMap<>();
    /** IP 发送滑动窗口：ip:{addr} -> 发送时间戳队列 */
    private final Map<String, Deque<Long>> ipSendBuckets = new ConcurrentHashMap<>();
    /** 全局发送滑动窗口（单队列，多实例尽力而为） */
    private final Deque<Long> globalSendBucket = new ArrayDeque<>();

    /**
     * 仅校验 IP/全局发送配额（不记账）。用于防枚举路径在「跳过发信」时与真实发信一致地返回 429。
     */
    public void assertSendQuota(String clientIp) {
        checkSendQuota(clientIp, false);
    }

    public void sendCode(String email, EmailCodePurpose purpose) {
        sendCode(email, purpose, null);
    }

    public void sendCode(String email, EmailCodePurpose purpose, String clientIp) {
        String normalized = normalizeEmail(email);
        String key = bucketKey(normalized, purpose);

        long now = System.currentTimeMillis();
        Long last = lastSendAt.get(key);
        int interval = authProperties.getCode().getSendIntervalSeconds() * 1000;
        if (last != null && now - last < interval) {
            long wait = (interval - (now - last) + 999) / 1000;
            throw new BusinessException(400, "发送过于频繁，请 " + wait + " 秒后再试");
        }

        // IP / 全局配额：在落库与发信前校验并记账
        checkSendQuota(clientIp, true);

        String code = generateCode(authProperties.getCode().getLength());
        LocalDateTime expires = LocalDateTime.now().plusMinutes(authProperties.getCode().getExpireMinutes());

        EmailCodeEntity entity = new EmailCodeEntity();
        entity.setEmail(normalized);
        entity.setCode(code);
        entity.setPurpose(purpose.name());
        entity.setExpiresAt(expires);
        entity.setUsed(0);
        entity.setCreatedAt(LocalDateTime.now());
        emailCodeMapper.insert(entity);

        lastSendAt.put(key, now);

        String subject = purpose == EmailCodePurpose.REGISTER
                ? "【AI工具台】注册验证码"
                : "【AI工具台】找回密码验证码";
        String body = "您的验证码是：" + code + "\n"
                + "有效期 " + authProperties.getCode().getExpireMinutes() + " 分钟，请勿泄露给他人。\n"
                + "如非本人操作，请忽略本邮件。";
        mailService.sendText(normalized, subject, body);
    }

    /**
     * 内存滑动窗口配额（风格对齐 {@link LoginRateLimiter}）。
     * 先校验 IP 与全局，再按需记账，避免一侧记账后另一侧拒绝导致配额虚耗。
     *
     * @param record true 时在未超限情况下记一次发送；false 仅检查
     */
    private void checkSendQuota(String clientIp, boolean record) {
        AuthProperties.Code cfg = authProperties.getCode();
        if (cfg == null || !cfg.isSendQuotaEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        int maxIp = cfg.getMaxSendsPerIpPerWindow();
        int maxGlobal = cfg.getMaxSendsGlobalPerWindow();
        long ipWindowMs = Math.max(1, cfg.getIpWindowSeconds()) * 1000L;
        long globalWindowMs = Math.max(1, cfg.getGlobalWindowSeconds()) * 1000L;
        String ipKey = "ip:" + (StringUtils.hasText(clientIp) ? clientIp.trim() : "unknown");

        // 全局队列为粗粒度锁：持锁期间一并处理 IP 桶，保证校验与记账原子
        synchronized (globalSendBucket) {
            if (maxGlobal > 0) {
                pruneWindow(globalSendBucket, now, globalWindowMs);
                if (globalSendBucket.size() >= maxGlobal) {
                    long waitSec = waitSeconds(globalSendBucket, now, globalWindowMs);
                    throw new BusinessException(429, "系统发送繁忙，请 " + waitSec + " 秒后再试");
                }
            }

            Deque<Long> ipQ = null;
            if (maxIp > 0) {
                ipQ = ipSendBuckets.computeIfAbsent(ipKey, k -> new ArrayDeque<>());
                // 已在 global 锁内，无需再锁 ipQ（ip 桶仅在此处读写）
                pruneWindow(ipQ, now, ipWindowMs);
                if (ipQ.size() >= maxIp) {
                    long waitSec = waitSeconds(ipQ, now, ipWindowMs);
                    throw new BusinessException(429, "发送过于频繁，请 " + waitSec + " 秒后再试");
                }
            }

            if (record) {
                if (maxGlobal > 0) {
                    globalSendBucket.addLast(now);
                }
                if (maxIp > 0 && ipQ != null) {
                    ipQ.addLast(now);
                }
            }
        }
    }

    private static void pruneWindow(Deque<Long> q, long now, long windowMs) {
        while (!q.isEmpty() && now - q.peekFirst() > windowMs) {
            q.pollFirst();
        }
    }

    private static long waitSeconds(Deque<Long> q, long now, long windowMs) {
        Long oldest = q.peekFirst();
        if (oldest == null) {
            return 1;
        }
        return Math.max(1, (windowMs - (now - oldest) + 999) / 1000);
    }

    public void verifyAndConsume(String email, String code, EmailCodePurpose purpose) {
        String normalized = normalizeEmail(email);
        String failKey = bucketKey(normalized, purpose);
        checkFailLimit(failKey);

        EmailCodeEntity latest = emailCodeMapper.selectOne(
                new LambdaQueryWrapper<EmailCodeEntity>()
                        .eq(EmailCodeEntity::getEmail, normalized)
                        .eq(EmailCodeEntity::getPurpose, purpose.name())
                        .eq(EmailCodeEntity::getUsed, 0)
                        .orderByDesc(EmailCodeEntity::getCreatedAt)
                        .last("LIMIT 1")
        );

        if (latest == null
                || latest.getExpiresAt() == null
                || latest.getExpiresAt().isBefore(LocalDateTime.now())
                || !latest.getCode().equals(code.trim())) {
            recordFail(failKey);
            throw new BusinessException(400, "验证码错误或已过期");
        }

        latest.setUsed(1);
        emailCodeMapper.updateById(latest);
        failBuckets.remove(failKey);
    }

    private void checkFailLimit(String key) {
        FailBucket bucket = failBuckets.get(key);
        if (bucket == null) {
            return;
        }
        long windowMs = authProperties.getCode().getVerifyFailWindowMinutes() * 60_000L;
        if (System.currentTimeMillis() - bucket.windowStart > windowMs) {
            failBuckets.remove(key);
            return;
        }
        if (bucket.count >= authProperties.getCode().getMaxVerifyFails()) {
            throw new BusinessException(429, "验证失败次数过多，请稍后再试");
        }
    }

    private void recordFail(String key) {
        long now = System.currentTimeMillis();
        long windowMs = authProperties.getCode().getVerifyFailWindowMinutes() * 60_000L;
        failBuckets.compute(key, (k, old) -> {
            if (old == null || now - old.windowStart > windowMs) {
                return new FailBucket(now, 1);
            }
            old.count++;
            return old;
        });
    }

    private String generateCode(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static String bucketKey(String email, EmailCodePurpose purpose) {
        return email + "|" + purpose.name();
    }

    private static class FailBucket {
        long windowStart;
        int count;

        FailBucket(long windowStart, int count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}
