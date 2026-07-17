package com.dwcode.okxbot.chat.agent;

import com.dwcode.okxbot.chat.config.AgentProperties;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 写工具确认草案：内存 + TTL（PR-2）。
 * 生产可换 Redis；confirmId 一次性消费。
 */
@Slf4j
@Service
public class ConfirmTokenService {

    private final AgentProperties agentProperties;
    private final ConcurrentHashMap<String, PendingConfirm> store = new ConcurrentHashMap<>();

    public ConfirmTokenService(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
    }

    @Value
    @Builder
    public static class PendingConfirm {
        String confirmId;
        Long userId;
        Long conversationId;
        String toolName;
        Map<String, Object> args;
        String summary;
        long expireAtMs;
    }

    public PendingConfirm create(Long userId,
                                 Long conversationId,
                                 String toolName,
                                 Map<String, Object> args,
                                 String summary) {
        purgeExpired();
        int ttl = Math.max(60, agentProperties.getConfirmTtlSeconds());
        String id = "c_" + UUID.randomUUID().toString().replace("-", "");
        PendingConfirm p = PendingConfirm.builder()
                .confirmId(id)
                .userId(userId)
                .conversationId(conversationId)
                .toolName(toolName)
                .args(args != null ? Map.copyOf(args) : Map.of())
                .summary(summary)
                .expireAtMs(System.currentTimeMillis() + ttl * 1000L)
                .build();
        store.put(id, p);
        log.info("创建确认草案: confirmId={}, tool={}, userId={}, ttl={}s",
                id, toolName, userId, ttl);
        return p;
    }

    /**
     * 取出并删除（一次性）。校验归属与过期。
     * 非本人访问不删除 token，避免误伤合法用户。
     *
     * @return null 表示无效/过期/非本人
     */
    public PendingConfirm consume(String confirmId, Long userId) {
        if (confirmId == null || confirmId.isBlank() || userId == null) {
            return null;
        }
        String id = confirmId.trim();
        PendingConfirm p = store.get(id);
        if (p == null) {
            return null;
        }
        if (p.getExpireAtMs() < System.currentTimeMillis()) {
            store.remove(id, p);
            log.info("确认草案已过期: confirmId={}", confirmId);
            return null;
        }
        if (!userId.equals(p.getUserId())) {
            log.warn("确认草案用户不匹配: confirmId={}, expect={}, actual={}",
                    confirmId, p.getUserId(), userId);
            return null;
        }
        // 本人且未过期：原子删除，保证一次性
        if (!store.remove(id, p)) {
            return null;
        }
        return p;
    }

    /**
     * 拒绝：删除草案并返回原 pending（便于落库改写消息）。
     *
     * @return null 表示无效/非本人
     */
    public PendingConfirm reject(String confirmId, Long userId) {
        if (confirmId == null || confirmId.isBlank() || userId == null) {
            return null;
        }
        String id = confirmId.trim();
        PendingConfirm p = store.get(id);
        if (p == null) {
            return null;
        }
        if (!userId.equals(p.getUserId())) {
            return null;
        }
        store.remove(p.getConfirmId());
        return p;
    }

    /** 草案是否仍有效（未过期且在内存中） */
    public boolean isActive(String confirmId) {
        if (confirmId == null || confirmId.isBlank()) {
            return false;
        }
        PendingConfirm p = store.get(confirmId.trim());
        if (p == null) {
            return false;
        }
        if (p.getExpireAtMs() < System.currentTimeMillis()) {
            store.remove(p.getConfirmId());
            return false;
        }
        return true;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> e.getValue().getExpireAtMs() < now);
    }
}
