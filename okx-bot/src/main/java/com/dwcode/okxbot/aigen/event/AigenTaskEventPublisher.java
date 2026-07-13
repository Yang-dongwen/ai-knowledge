package com.dwcode.okxbot.aigen.event;

import com.dwcode.okxbot.aigen.entity.AigenTaskEntity;
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
 * AI 视频生成任务 SSE（按 userId fan-out）。
 * 单机内存实现，模式对齐 VideoTaskEventPublisher。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AigenTaskEventPublisher {

    public static final String TYPE_CONNECTED = "connected";
    public static final String TYPE_PING = "ping";
    public static final String TYPE_CREATED = "task.created";
    public static final String TYPE_STATUS = "task.status";
    public static final String TYPE_DELETED = "task.deleted";

    private static final long SSE_TIMEOUT_MS = 60L * 60 * 1000;
    private static final int MAX_EMITTERS_PER_USER = 3;
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> userEmitters =
            new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        CopyOnWriteArrayList<SseEmitter> list =
                userEmitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());

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
                    .data(objectMapper.writeValueAsString(payload), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            cleanup.run();
            log.debug("aigen SSE 初始推送失败 userId={}: {}", userId, e.getMessage());
        }
        log.debug("aigen SSE 订阅: userId={}, connections={}", userId, list.size());
        return emitter;
    }

    public void publishEntity(AigenTaskEntity entity, String type) {
        if (entity == null || entity.getUserId() == null || entity.getId() == null) {
            return;
        }
        publish(entity.getUserId(), type, String.valueOf(entity.getId()), toLightData(entity));
    }

    public void publishDeleted(Long userId, Long taskId) {
        if (userId == null || taskId == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(taskId));
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
            log.warn("aigen SSE 序列化失败: {}", e.getMessage());
            return;
        }
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(type).data(json, MediaType.APPLICATION_JSON));
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
                    emitter.send(SseEmitter.event().name(TYPE_PING).data(json, MediaType.APPLICATION_JSON));
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
            m.put("id", taskId);
        }
        return m;
    }

    private Map<String, Object> toLightData(AigenTaskEntity e) {
        boolean outputAvailable = e.getOutputPath() != null
                && !e.getOutputPath().isBlank()
                && Files.isRegularFile(Path.of(e.getOutputPath()));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", String.valueOf(e.getId()));
        d.put("taskId", String.valueOf(e.getId()));
        d.put("title", e.getTitle());
        d.put("prompt", e.getPrompt());
        d.put("templateId", e.getTemplateId());
        d.put("status", e.getStatus());
        d.put("currentStep", e.getCurrentStep());
        d.put("progress", e.getProgress() != null ? e.getProgress() : 0);
        d.put("language", e.getLanguage());
        d.put("aspectRatio", e.getAspectRatio());
        d.put("targetDurationSec", e.getTargetDurationSec());
        d.put("llmProvider", e.getLlmProvider());
        d.put("llmModel", e.getLlmModel());
        // 空串而非 null，配合全局 non_null 序列化，保证前端能清掉旧错误
        d.put("errorMessage", e.getErrorMessage() == null ? "" : e.getErrorMessage());
        d.put("durationSeconds", e.getDurationSeconds());
        d.put("outputAvailable", outputAvailable);
        d.put("planDurationMs", e.getPlanDurationMs() != null ? e.getPlanDurationMs() : 0L);
        d.put("assetDurationMs", e.getAssetDurationMs() != null ? e.getAssetDurationMs() : 0L);
        d.put("renderDurationMs", e.getRenderDurationMs() != null ? e.getRenderDurationMs() : 0L);
        d.put("totalDurationMs", e.getTotalDurationMs() != null ? e.getTotalDurationMs() : 0L);
        d.put("startedAt", formatTime(e.getStartedAt()));
        d.put("finishedAt", formatTime(e.getFinishedAt()));
        d.put("createdAt", formatTime(e.getCreatedAt()));
        d.put("updatedAt", formatTime(e.getUpdatedAt()));
        return d;
    }

    private static String formatTime(LocalDateTime t) {
        return t == null ? null : DT_FMT.format(t);
    }
}
