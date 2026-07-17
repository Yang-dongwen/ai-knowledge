package com.dwcode.okxbot.chat.agent;

import com.dwcode.okxbot.chat.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 过期 confirm 用例（通过反射把 expireAtMs 打到过去）。
 */
class ConfirmTokenExpiryTest {

    @Test
    @SuppressWarnings("unchecked")
    void consume_expired_returnsNull() throws Exception {
        AgentProperties props = new AgentProperties();
        props.setConfirmTtlSeconds(900);
        ConfirmTokenService service = new ConfirmTokenService(props);

        ConfirmTokenService.PendingConfirm p = service.create(
                1L, 10L, "draft_imggen", Map.of("prompt", "x"), "x");
        assertTrue(service.isActive(p.getConfirmId()));

        // 反射拿到 store，替换为已过期条目
        Field storeField = ConfirmTokenService.class.getDeclaredField("store");
        storeField.setAccessible(true);
        ConcurrentHashMap<String, ConfirmTokenService.PendingConfirm> store =
                (ConcurrentHashMap<String, ConfirmTokenService.PendingConfirm>) storeField.get(service);

        ConfirmTokenService.PendingConfirm expired = ConfirmTokenService.PendingConfirm.builder()
                .confirmId(p.getConfirmId())
                .userId(p.getUserId())
                .conversationId(p.getConversationId())
                .toolName(p.getToolName())
                .args(p.getArgs())
                .summary(p.getSummary())
                .expireAtMs(System.currentTimeMillis() - 1000L)
                .build();
        store.put(p.getConfirmId(), expired);

        assertNull(service.consume(p.getConfirmId(), 1L));
        assertFalse(service.isActive(p.getConfirmId()));
    }
}
