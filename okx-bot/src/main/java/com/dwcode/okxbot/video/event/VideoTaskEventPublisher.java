package com.dwcode.okxbot.video.event;

import com.dwcode.okxbot.video.entity.VideoTaskEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 视频任务 SSE 推送（按 userId fan-out）。
 * 单机内存实现；多实例后续可换 Redis Pub/Sub。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoTaskEventPublisher {

    public static final String TYPE_CONNECTED = "connected";
    public static final String TYPE_PING = "ping";
    public static final String TYPE_CREATED = "task.created";
    public static final String TYPE_STATUS = "task.status";
    public static final String TYPE_DELETED = "task.deleted";

    private static final long SSE_TIMEOUT_MS = 60L * 60 * 1000;
    private static final int MAX_EMITTERS_PER_USER = 3;
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper objectMapper;

    /** userId → emitters */
    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> userEmitters =
            new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        CopyOnWriteArrayList<SseEmitter> list =
                userEmitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());

        // 限制连接数：踢掉最旧
        while (list.size() >= MAX_EMITTERS_PER_USER) {
            SseEmitter old = list.remove(0);
            try {
                old.complete();
            } catch (Exception ignored) {
                // ignore
            }
        }
        list.add(emitter);

        Runnable cleanup = () -> removeEmitter(userId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            Map<String, Object> payload = baseEnvelope(TYPE_CONNECTED, null);
            payload.put("data", Map.of("message", "ok", "userId", String.valueOf(userId)));
            emitter.send(SseEmitter.event()
                    .name(TYPE_CONNECTED)
                    .data(objectMapper.writeValueAsString(payload), MediaType.TEXT_PLAIN));
        } catch (IOException e) {
            cleanup.run();
            log.debug("SSE 初始推送失败 userId={}: {}", userId, e.getMessage());
        }
        log.debug("SSE 订阅: userId={}, connections={}", userId, list.size());
        return emitter;
    }

    public void publishEntity(VideoTaskEntity entity, String type) {
        if (entity == null || entity.getUserId() == null || entity.getId() == null) {
            return;
        }
        Map<String, Object> data = toLightData(entity);
        publish(entity.getUserId(), type, String.valueOf(entity.getId()), data);
    }

    public void publishDeleted(Long userId, Long taskId) {
        if (userId == null || taskId == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", String.valueOf(taskId));
        publish(userId, TYPE_DELETED, String.valueOf(taskId), data);
    }

    public void publish(Long userId, String type, String taskId, Map<String, Object> data) {
        if (userId == null) {
            return;
        }
        CopyOnWriteArrayList<SseEmitter> list = userEmitters.get(userId);
        if (list == null || list.isEmpty()) {
            return;
        }
        Map<String, Object> envelope = baseEnvelope(type, taskId);
        envelope.put("data", data != null ? data : Map.of());
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.warn("SSE 序列化失败: {}", e.getMessage());
            return;
        }
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(type).data(json, MediaType.TEXT_PLAIN));
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        for (SseEmitter d : dead) {
            removeEmitter(userId, d);
            try {
                d.complete();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        if (userEmitters.isEmpty()) {
            return;
        }
        Map<String, Object> envelope = baseEnvelope(TYPE_PING, null);
        envelope.put("data", Map.of("ts", System.currentTimeMillis()));
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            return;
        }
        for (Map.Entry<Long, CopyOnWriteArrayList<SseEmitter>> e : userEmitters.entrySet()) {
            List<SseEmitter> dead = new ArrayList<>();
            for (SseEmitter emitter : e.getValue()) {
                try {
                    emitter.send(SseEmitter.event().name(TYPE_PING).data(json, MediaType.TEXT_PLAIN));
                } catch (Exception ex) {
                    dead.add(emitter);
                }
            }
            for (SseEmitter d : dead) {
                removeEmitter(e.getKey(), d);
            }
        }
    }

    private void removeEmitter(Long userId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = userEmitters.get(userId);
        if (list == null) {
            return;
        }
        list.remove(emitter);
        if (list.isEmpty()) {
            userEmitters.remove(userId, list);
        }
    }

    private static Map<String, Object> baseEnvelope(String type, String taskId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("ts", System.currentTimeMillis());
        if (taskId != null) {
            m.put("taskId", taskId);
        }
        return m;
    }

    private Map<String, Object> toLightData(VideoTaskEntity e) {
        boolean videoAvailable = e.getVideoPath() != null
                && !e.getVideoPath().isBlank()
                && Files.isRegularFile(Path.of(e.getVideoPath()));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("taskId", String.valueOf(e.getId()));
        d.put("status", e.getStatus());
        d.put("url", e.getSourceUrl());
        d.put("title", e.getTitle());
        d.put("platform", e.getPlatform());
        d.put("llmProvider", e.getLlmProvider());
        d.put("llmModel", e.getLlmModel());
        d.put("understandingMode", e.getUnderstandingMode());
        d.put("omniProvider", e.getOmniProvider());
        d.put("omniModel", e.getOmniModel());
        d.put("currentStep", e.getCurrentStep());
        d.put("errorMessage", e.getErrorMessage());
        d.put("durationSeconds", e.getDurationSeconds());
        d.put("videoAvailable", videoAvailable);
        d.put("createdAt", formatTime(e.getCreatedAt()));
        d.put("startedAt", formatTime(e.getStartedAt()));
        d.put("finishedAt", formatTime(e.getFinishedAt()));
        d.put("downloadDurationMs", e.getDownloadDurationMs());
        d.put("transcribeDurationMs", e.getTranscribeDurationMs());
        d.put("understandDurationMs", e.getUnderstandDurationMs());
        d.put("summarizeDurationMs", e.getSummarizeDurationMs());
        d.put("totalDurationMs", e.getTotalDurationMs());
        d.put("degraded", e.getDegraded() != null && e.getDegraded() == 1);
        d.put("degradeReason", e.getDegradeReason());
        return d;
    }

    private static String formatTime(LocalDateTime t) {
        return t == null ? null : DT_FMT.format(t);
    }
}
