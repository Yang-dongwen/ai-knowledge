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

import java.security.SecureRandom;
import java.time.LocalDateTime;
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

    public void sendCode(String email, EmailCodePurpose purpose) {
        String normalized = normalizeEmail(email);
        String key = bucketKey(normalized, purpose);

        long now = System.currentTimeMillis();
        Long last = lastSendAt.get(key);
        int interval = authProperties.getCode().getSendIntervalSeconds() * 1000;
        if (last != null && now - last < interval) {
            long wait = (interval - (now - last) + 999) / 1000;
            throw new BusinessException(400, "发送过于频繁，请 " + wait + " 秒后再试");
        }

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
