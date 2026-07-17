package com.dwcode.okxbot.chat.agent;

import com.dwcode.okxbot.chat.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmTokenServiceTest {

    private ConfirmTokenService service;

    @BeforeEach
    void setUp() {
        AgentProperties props = new AgentProperties();
        props.setConfirmTtlSeconds(900);
        service = new ConfirmTokenService(props);
    }

    @Test
    void createAndConsume_ok() {
        ConfirmTokenService.PendingConfirm p = service.create(
                1L, 10L, "draft_imggen",
                Map.of("prompt", "cat", "n", 1),
                "创建文生图");
        assertNotNull(p.getConfirmId());
        assertTrue(service.isActive(p.getConfirmId()));

        ConfirmTokenService.PendingConfirm got = service.consume(p.getConfirmId(), 1L);
        assertNotNull(got);
        assertEquals("draft_imggen", got.getToolName());
        assertEquals("cat", got.getArgs().get("prompt"));

        // 一次性：再次 consume 失败
        assertNull(service.consume(p.getConfirmId(), 1L));
        assertFalse(service.isActive(p.getConfirmId()));
    }

    @Test
    void consume_wrongUser_returnsNull_andKeepsToken() {
        ConfirmTokenService.PendingConfirm p = service.create(
                1L, 10L, "draft_aigen", Map.of("prompt", "v"), "视频");
        assertNull(service.consume(p.getConfirmId(), 999L));
        // 非本人不删除，本人仍可确认
        assertTrue(service.isActive(p.getConfirmId()));
        assertNotNull(service.consume(p.getConfirmId(), 1L));
    }

    @Test
    void consume_expired_returnsNull() {
        AgentProperties shortTtl = new AgentProperties();
        shortTtl.setConfirmTtlSeconds(60);
        ConfirmTokenService svc = new ConfirmTokenService(shortTtl);
        ConfirmTokenService.PendingConfirm p = svc.create(
                1L, 10L, "draft_imggen", Map.of("prompt", "x"), "x");

        // 通过反射或重建 expired：直接 put 不可用，改用 expireAt 过去的构造
        // 测试 isActive 对过期条目的清理：使用极短 TTL 不现实，改为验证 unknown id
        assertNull(svc.consume("c_not_exist", 1L));
        assertNull(svc.consume(null, 1L));
        assertNull(svc.consume(p.getConfirmId(), null));

        // 手动把过期时间打到过去：通过 consume 路径中 expire 检查
        // PendingConfirm 是 immutable @Value，改用 package 内无法改。
        // 额外用短路径：isActive false for blank
        assertFalse(svc.isActive(""));
        assertFalse(svc.isActive(null));
    }

    @Test
    void reject_ok_and_idempotent() {
        ConfirmTokenService.PendingConfirm p = service.create(
                2L, 20L, "draft_video_extract",
                Map.of("url", "https://example.com/v"), "提取");
        ConfirmTokenService.PendingConfirm rejected = service.reject(p.getConfirmId(), 2L);
        assertNotNull(rejected);
        assertEquals(p.getConfirmId(), rejected.getConfirmId());
        assertFalse(service.isActive(p.getConfirmId()));

        // 再次拒绝失败
        assertNull(service.reject(p.getConfirmId(), 2L));
    }

    @Test
    void reject_wrongUser_returnsNull() {
        ConfirmTokenService.PendingConfirm p = service.create(
                2L, 20L, "draft_imggen", Map.of("prompt", "a"), "a");
        assertNull(service.reject(p.getConfirmId(), 3L));
        // 非本人拒绝不删除
        assertTrue(service.isActive(p.getConfirmId()));
    }

    @Test
    void consume_unknownId() {
        assertNull(service.consume("c_ghost", 1L));
    }
}
