package com.dwcode.okxbot.auth.service;

import com.dwcode.okxbot.auth.config.AuthProperties;
import com.dwcode.okxbot.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录 / 绑定等口令校验：按邮箱 + IP 滑动窗口限流（内存实现，多实例尽力而为）。
 */
@Component
@RequiredArgsConstructor
public class LoginRateLimiter {

    private final AuthProperties authProperties;
    private final Map<String, Deque<Long>> failBuckets = new ConcurrentHashMap<>();
    private final Map<String, Long> lockUntil = new ConcurrentHashMap<>();

    public void assertAllowed(String email, String clientIp) {
        AuthProperties.LoginLimit lim = authProperties.getLoginLimit();
        if (lim == null || !lim.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String key : keys(email, clientIp)) {
            Long until = lockUntil.get(key);
            if (until != null && until > now) {
                long sec = Math.max(1, (until - now + 999) / 1000);
                throw new BusinessException(429, "尝试过多，请 " + sec + " 秒后再试");
            }
            if (until != null && until <= now) {
                lockUntil.remove(key);
            }
        }
    }

    public void recordFailure(String email, String clientIp) {
        AuthProperties.LoginLimit lim = authProperties.getLoginLimit();
        if (lim == null || !lim.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long windowMs = Math.max(1, lim.getWindowSeconds()) * 1000L;
        int maxFails = Math.max(1, lim.getMaxFails());
        long lockMs = Math.max(1, lim.getLockSeconds()) * 1000L;
        for (String key : keys(email, clientIp)) {
            Deque<Long> q = failBuckets.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (q) {
                while (!q.isEmpty() && now - q.peekFirst() > windowMs) {
                    q.pollFirst();
                }
                q.addLast(now);
                if (q.size() >= maxFails) {
                    lockUntil.put(key, now + lockMs);
                    q.clear();
                }
            }
        }
    }

    public void recordSuccess(String email, String clientIp) {
        for (String key : keys(email, clientIp)) {
            failBuckets.remove(key);
            lockUntil.remove(key);
        }
    }

    private static String[] keys(String email, String clientIp) {
        String e = StringUtils.hasText(email) ? email.trim().toLowerCase() : "unknown";
        String ip = StringUtils.hasText(clientIp) ? clientIp.trim() : "unknown";
        return new String[]{"e:" + e, "ip:" + ip};
    }
}
