package com.dwcode.okxbot.imggen.event;

import com.dwcode.okxbot.imggen.entity.ImgGenTaskEntity;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class ImgGenTaskEventPublisher {

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
                    .data(objectMapper.writeValueAsString(payload), MediaType.TEXT_PLAIN));
        } catch (IOException e) {
            cleanup.run();
        }
        return emitter;
    }

    public void publishEntity(ImgGenTaskEntity entity, String type) {
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
            m.put("id", taskId);
        }
        return m;
    }

    private Map<String, Object> toLightData(ImgGenTaskEntity e) {
        boolean outputAvailable = e.getCoverPath() != null
                && !e.getCoverPath().isBlank()
                && Files.isRegularFile(Path.of(e.getCoverPath()));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", String.valueOf(e.getId()));
        d.put("taskId", String.valueOf(e.getId()));
        d.put("title", e.getTitle());
        d.put("prompt", e.getPrompt());
        d.put("enhancedPrompt", e.getEnhancedPrompt());
        d.put("status", e.getStatus());
        d.put("currentStep", e.getCurrentStep());
        d.put("progress", e.getProgress() != null ? e.getProgress() : 0);
        d.put("aspectRatio", e.getAspectRatio());
        d.put("width", e.getWidth());
        d.put("height", e.getHeight());
        d.put("n", e.getN());
        d.put("model", e.getModel());
        d.put("provider", e.getProvider());
        d.put("enhanceEnabled", e.getEnhanceEnabled() != null && e.getEnhanceEnabled() == 1);
        d.put("llmProvider", e.getLlmProvider());
        d.put("llmModel", e.getLlmModel());
        d.put("errorMessage", e.getErrorMessage() == null ? "" : e.getErrorMessage());
        d.put("outputAvailable", outputAvailable);
        d.put("enhanceDurationMs", e.getEnhanceDurationMs() != null ? e.getEnhanceDurationMs() : 0L);
        d.put("generateDurationMs", e.getGenerateDurationMs() != null ? e.getGenerateDurationMs() : 0L);
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
