package com.dwcode.okxbot.kb.service;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.kb.config.KbProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分片上传并发闸门（单进程）。多实例需另上分布式锁。
 */
@Component
public class KbUploadLimiter {

    private final KbProperties kbProperties;
    private final Semaphore globalParts;
    private final Semaphore globalComplete;
    private final ConcurrentHashMap<Long, Semaphore> userParts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> userSessions = new ConcurrentHashMap<>();

    public KbUploadLimiter(KbProperties kbProperties) {
        this.kbProperties = kbProperties;
        KbProperties.File conf = kbProperties.getFile();
        this.globalParts = new Semaphore(Math.max(1, conf.getMaxConcurrentPartsGlobal()));
        this.globalComplete = new Semaphore(Math.max(1, conf.getMaxConcurrentCompleteGlobal()));
    }

    public void acquireSession(long userId) {
        int max = Math.max(1, kbProperties.getFile().getMaxSessionsPerUser());
        AtomicInteger n = userSessions.computeIfAbsent(userId, id -> new AtomicInteger());
        while (true) {
            int cur = n.get();
            if (cur >= max) {
                throw busy("同时上传的文件过多，请等待当前任务完成");
            }
            if (n.compareAndSet(cur, cur + 1)) {
                return;
            }
        }
    }

    public void releaseSession(long userId) {
        AtomicInteger n = userSessions.get(userId);
        if (n == null) {
            return;
        }
        n.updateAndGet(v -> Math.max(0, v - 1));
    }

    public Permit acquirePart(long userId) {
        if (!globalParts.tryAcquire()) {
            throw busy("上传繁忙，请稍后重试");
        }
        Semaphore user = userParts.computeIfAbsent(userId,
                id -> new Semaphore(Math.max(1, kbProperties.getFile().getMaxConcurrentPartsPerUser())));
        if (!user.tryAcquire()) {
            globalParts.release();
            throw busy("上传繁忙，请稍后重试");
        }
        return () -> {
            user.release();
            globalParts.release();
        };
    }

    public Permit acquireComplete() {
        if (!globalComplete.tryAcquire()) {
            throw busy("文件合并繁忙，请稍后重试");
        }
        return globalComplete::release;
    }

    private static BusinessException busy(String msg) {
        return new BusinessException(429, msg);
    }

    @FunctionalInterface
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
