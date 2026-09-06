package com.dwcode.okxbot.common.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UserSseHubTest {

    private UserSseHub hub;

    @BeforeEach
    void setUp() {
        hub = new UserSseHub(new ObjectMapper());
    }

    @Test
    void envelopeVideoHasTaskIdOnly() {
        Map<String, Object> m = UserSseHub.envelope("task.status", "42", false);
        assertEquals("task.status", m.get("type"));
        assertEquals("42", m.get("taskId"));
        assertFalse(m.containsKey("id"));
        assertTrue(m.containsKey("ts"));
    }

    @Test
    void envelopeOthersAliasId() {
        Map<String, Object> m = UserSseHub.envelope("task.status", "42", true);
        assertEquals("42", m.get("taskId"));
        assertEquals("42", m.get("id"));
    }

    @Test
    void envelopePingOmitsTaskId() {
        Map<String, Object> m = UserSseHub.envelope(SseEventTypes.PING, null, true);
        assertEquals(SseEventTypes.PING, m.get("type"));
        assertFalse(m.containsKey("taskId"));
        assertFalse(m.containsKey("id"));
    }

    @Test
    void subscribeCapsAtThreePerUser() {
        Long userId = 7L;
        hub.subscribe("aigen", userId);
        hub.subscribe("aigen", userId);
        hub.subscribe("aigen", userId);
        hub.subscribe("aigen", userId);
        assertEquals(UserSseHub.MAX_EMITTERS_PER_USER, hub.connectionCount("aigen", userId));
    }

    @Test
    void channelsAreIsolated() {
        Long userId = 9L;
        hub.subscribe("video", userId);
        hub.subscribe("aigen", userId);
        assertEquals(1, hub.connectionCount("video", userId));
        assertEquals(1, hub.connectionCount("aigen", userId));
        assertEquals(0, hub.connectionCount("article", userId));
    }

    @Test
    void publishToMissingSubscriberIsNoop() {
        assertDoesNotThrow(() ->
                hub.publish("video", 1L, SseEventTypes.STATUS, "1", Map.of("status", "PENDING"), false));
        assertEquals(0, hub.connectionCount("video", 1L));
    }

    @Test
    void subscribeReturnsEmitter() {
        SseEmitter emitter = hub.subscribe("imggen", 3L);
        assertNotNull(emitter);
        assertEquals(1, hub.connectionCount("imggen", 3L));
    }
}
