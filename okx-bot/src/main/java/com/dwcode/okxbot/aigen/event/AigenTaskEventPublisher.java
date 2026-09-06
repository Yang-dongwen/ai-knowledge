package com.dwcode.okxbot.aigen.event;

import com.dwcode.okxbot.aigen.entity.AigenTaskEntity;
import com.dwcode.okxbot.common.sse.SseEventTypes;
import com.dwcode.okxbot.common.sse.UserSseHub;
import com.dwcode.okxbot.storage.ObjectKeyBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 视频生成任务 SSE。连接管理在 {@link UserSseHub}（channel=aigen）。
 */
@Component
@RequiredArgsConstructor
public class AigenTaskEventPublisher {

    public static final String CHANNEL = "aigen";
    public static final String TYPE_CONNECTED = SseEventTypes.CONNECTED;
    public static final String TYPE_PING = SseEventTypes.PING;
    public static final String TYPE_CREATED = SseEventTypes.CREATED;
    public static final String TYPE_STATUS = SseEventTypes.STATUS;
    public static final String TYPE_DELETED = SseEventTypes.DELETED;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserSseHub sseHub;

    public SseEmitter subscribe(Long userId) {
        return sseHub.subscribe(CHANNEL, userId);
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
        sseHub.publish(CHANNEL, userId, type, taskId, data, true);
    }

    private Map<String, Object> toLightData(AigenTaskEntity e) {
        boolean outputAvailable = false;
        String op = e.getOutputPath();
        if (op != null && !op.isBlank()) {
            if (ObjectKeyBuilder.looksLikeLocalAbsolutePath(op)) {
                try {
                    outputAvailable = Files.isRegularFile(Path.of(op));
                } catch (Exception ignored) {
                    outputAvailable = false;
                }
            } else {
                outputAvailable = true;
            }
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", String.valueOf(e.getId()));
        d.put("taskId", String.valueOf(e.getId()));
        d.put("title", e.getTitle());
        d.put("prompt", e.getPrompt());
        d.put("templateId", e.getTemplateId());
        d.put("status", e.getStatus());
        d.put("currentStep", e.getCurrentStep());
        d.put("progress", e.getProgress() != null ? e.getProgress() : 0);
        d.put("pipelineMode", e.getPipelineMode());
        d.put("audioMode", e.getAudioMode());
        d.put("shotCount", e.getShotCount());
        d.put("assetDoneCount", e.getAssetDoneCount());
        d.put("language", e.getLanguage());
        d.put("aspectRatio", e.getAspectRatio());
        d.put("targetDurationSec", e.getTargetDurationSec());
        d.put("llmProvider", e.getLlmProvider());
        d.put("llmModel", e.getLlmModel());
        d.put("imageProvider", e.getImageProvider());
        d.put("imageModel", e.getImageModel());
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
