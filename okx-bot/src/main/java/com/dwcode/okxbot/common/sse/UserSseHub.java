package com.dwcode.okxbot.common.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 按 channel + userId 的 SSE fan-out（观察者连接管理）。
 * <p>
 * 四套任务（video / aigen / article / imggen）共用连接、心跳、死连接清理；
 * <b>channel 分桶</b>，避免订阅成片页的连接收到视频提取事件。
 * <p>
 * 必须用 {@link MediaType#TEXT_PLAIN} 发送已序列化 JSON：
 * {@code APPLICATION_JSON} 会把 String 再包一层引号，前端解析失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSseHub {

    static final long SSE_TIMEOUT_MS = 60L * 60 * 1000;
    static final int MAX_EMITTERS_PER_USER = 3;

    private final ObjectMapper objectMapper;

    /** channel → userId → emitters */
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>> channels =
            new ConcurrentHashMap<>();

    /**
     * @param aliasId 为 true 时 envelope 同时写 {@code id}（aigen/article/imggen）；
     *                video 历史只有 {@code taskId}
     */
    public void publish(String channel, Long userId, String type, String taskId,
                        Map<String, Object> data, boolean aliasId) {
        if (userId == null) {
            return;
        }
        CopyOnWriteArrayList<SseEmitter> list = emittersOf(channel, userId);
        if (list == null || list.isEmpty()) {
            return;
        }
        Map<String, Object> env = envelope(type, taskId, aliasId);
        env.put("data", data != null ? data : Map.of());
        String json;
        try {
            json = objectMapper.writeValueAsString(env);
        } catch (Exception e) {
            log.warn("SSE 序列化失败 channel={} type={}: {}", channel, type, e.getMessage());
            return;
        }
        sendTo(channel, userId, list, type, json, true);
    }

    public SseEmitter subscribe(String channel, Long userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        CopyOnWriteArrayList<SseEmitter> list =
                users(channel).computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());

        while (list.size() >= MAX_EMITTERS_PER_USER) {
            SseEmitter old = list.remove(0);
            try {
                old.complete();
            } catch (Exception ignored) {
                // ignore
            }
        }
        list.add(emitter);

        Runnable cleanup = () -> removeEmitter(channel, userId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            Map<String, Object> payload = envelope(SseEventTypes.CONNECTED, null, false);
            payload.put("data", Map.of("message", "ok", "userId", String.valueOf(userId)));
            emitter.send(SseEmitter.event()
                    .name(SseEventTypes.CONNECTED)
                    .data(objectMapper.writeValueAsString(payload), MediaType.TEXT_PLAIN));
        } catch (IOException e) {
            cleanup.run();
            log.debug("SSE 初始推送失败 channel={} userId={}: {}", channel, userId, e.getMessage());
        }
        log.debug("SSE 订阅: channel={}, userId={}, connections={}", channel, userId, list.size());
        return emitter;
    }

    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        if (channels.isEmpty()) {
            return;
        }
        Map<String, Object> env = envelope(SseEventTypes.PING, null, false);
        env.put("data", Map.of("ts", System.currentTimeMillis()));
        String json;
        try {
            json = objectMapper.writeValueAsString(env);
        } catch (Exception e) {
            return;
        }
        for (Map.Entry<String, ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>> ch : channels.entrySet()) {
            String channel = ch.getKey();
            for (Map.Entry<Long, CopyOnWriteArrayList<SseEmitter>> e : ch.getValue().entrySet()) {
                sendTo(channel, e.getKey(), e.getValue(), SseEventTypes.PING, json, false);
            }
        }
    }

    /**
     * 线格式：{@code type} + {@code ts}；有 taskId 时写 {@code taskId}，可选 {@code id} 别名。
     */
    static Map<String, Object> envelope(String type, String taskId, boolean aliasId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("ts", System.currentTimeMillis());
        if (taskId != null) {
            m.put("taskId", taskId);
            if (aliasId) {
                m.put("id", taskId);
            }
        }
        return m;
    }

    int connectionCount(String channel, Long userId) {
        CopyOnWriteArrayList<SseEmitter> list = emittersOf(channel, userId);
        return list == null ? 0 : list.size();
    }

    private ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> users(String channel) {
        return channels.computeIfAbsent(channel, c -> new ConcurrentHashMap<>());
    }

    private CopyOnWriteArrayList<SseEmitter> emittersOf(String channel, Long userId) {
        ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> users = channels.get(channel);
        if (users == null) {
            return null;
        }
        return users.get(userId);
    }

    private void sendTo(String channel, Long userId, CopyOnWriteArrayList<SseEmitter> list,
                        String eventName, String json, boolean completeDead) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(json, MediaType.TEXT_PLAIN));
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        for (SseEmitter d : dead) {
            removeEmitter(channel, userId, d);
            if (completeDead) {
                try {
                    d.complete();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }

    private void removeEmitter(String channel, Long userId, SseEmitter emitter) {
        ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> users = channels.get(channel);
        if (users == null) {
            return;
        }
        CopyOnWriteArrayList<SseEmitter> list = users.get(userId);
        if (list == null) {
            return;
        }
        list.remove(emitter);
        if (list.isEmpty()) {
            users.remove(userId, list);
        }
        if (users.isEmpty()) {
            channels.remove(channel, users);
        }
    }
}
