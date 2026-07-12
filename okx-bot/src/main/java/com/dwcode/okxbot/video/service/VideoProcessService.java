package com.dwcode.okxbot.video.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.chat.config.AiProperties.ProviderConfig;
import com.dwcode.okxbot.video.agent.VideoTaskScheduler;
import com.dwcode.okxbot.video.client.LlmChatClient;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.dto.*;
import com.dwcode.okxbot.video.entity.VideoTaskEntity;
import com.dwcode.okxbot.video.enums.VideoTaskStatus;
import com.dwcode.okxbot.video.mapper.VideoTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 视频处理任务服务（对外业务入口）。
 *
 * 职责：创建任务、异步触发流水线、查询状态、转录/摘要/视频下载。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoProcessService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final VideoTaskMapper videoTaskMapper;
    private final VideoTaskScheduler taskScheduler;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;
    private final VideoProperties videoProperties;
    private final LlmChatClient llmChatClient;
    private final AiModelConfigService aiModelConfigService;

    /**
     * 提交处理任务，立即返回 taskId，后台异步执行。
     */
    public VideoTaskResponse submit(VideoProcessRequest request) {
        VideoProcessOptions options = request.getOptions() != null
                ? request.getOptions()
                : new VideoProcessOptions();

        String llmProvider = blankToNull(options.getLlmProvider());
        String llmModel = blankToNull(options.getLlmModel());
        if (llmProvider == null) {
            llmProvider = blankToNull(videoProperties.getLlm().getProvider());
        }
        if (llmProvider == null) {
            // defaultProvider 字段为 key；getDefaultProvider() 返回配置对象
            llmProvider = blankToNull(aiProperties.getDefaultProvider() != null
                    ? findProviderKey(aiProperties.getDefaultProvider())
                    : null);
            if (llmProvider == null) {
                llmProvider = firstAvailableProviderKey();
            }
        }
        // 校验供应商与模型（模型列表来自数据库）
        if (llmProvider != null) {
            ProviderConfig pc = aiProperties.getProvider(llmProvider);
            if (pc == null || pc.getApiKey() == null || pc.getApiKey().isEmpty()) {
                throw new BusinessException("LLM 供应商不可用或未配置 api-key: " + llmProvider);
            }
            if (llmModel == null) {
                llmModel = aiModelConfigService.firstEnabledModelId(llmProvider);
            }
            if (llmModel == null) {
                throw new BusinessException("未配置可用 LLM 模型，请在「模型管理」中添加");
            }
        }

        VideoTaskEntity entity = new VideoTaskEntity();
        entity.setSourceUrl(request.getUrl().trim());
        entity.setPlatform(storageService.detectPlatform(entity.getSourceUrl()));
        entity.setStatus(VideoTaskStatus.PENDING.name());
        entity.setCurrentStep("排队中");
        entity.setLanguage(options.getLanguage() != null ? options.getLanguage() : "zh");
        entity.setLlmProvider(llmProvider);
        entity.setLlmModel(llmModel);
        entity.setExtractMindMap(Boolean.FALSE.equals(options.getExtractMindMap()) ? 0 : 1);
        entity.setGenerateRepurposeScript(Boolean.FALSE.equals(options.getGenerateRepurposeScript()) ? 0 : 1);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        videoTaskMapper.insert(entity);

        log.info("创建视频任务: taskId={}, platform={}, llm={}/{}, url={}",
                entity.getId(), entity.getPlatform(), llmProvider, llmModel, entity.getSourceUrl());
        // 进入排队，由调度器按并发槽位启动
        taskScheduler.notifyPending();

        return toResponse(entity, false);
    }

    /**
     * 测试指定模型是否可用。
     */
    public LlmModelTestResponse testLlmModel(LlmModelTestRequest request) {
        return llmChatClient.testModel(request.getProvider().trim(), request.getModel().trim());
    }

    /**
     * 暂停进行中的任务：协作式中断当前流水线，并调度排队中的 PENDING 任务。
     */
    public VideoTaskResponse pauseTask(Long taskId) {
        VideoTaskEntity entity = requireTask(taskId);
        String status = entity.getStatus();
        if (!VideoTaskStatus.DOWNLOADING.name().equals(status)
                && !VideoTaskStatus.TRANSCRIBING.name().equals(status)
                && !VideoTaskStatus.SUMMARIZING.name().equals(status)
                && !VideoTaskStatus.PENDING.name().equals(status)) {
            throw new BusinessException(400, "仅排队中或进行中的任务可暂停，当前状态: " + status);
        }

        // PENDING：直接标记暂停，不进入执行
        if (VideoTaskStatus.PENDING.name().equals(status)) {
            entity.setStatus(VideoTaskStatus.PAUSED.name());
            entity.setCurrentStep("已暂停（未开始执行）");
            entity.setFinishedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            videoTaskMapper.updateById(entity);
            taskScheduler.markFinished(taskId);
            log.info("排队任务已暂停: taskId={}", taskId);
            return toResponse(entity, false);
        }

        // 进行中：发暂停信号，流水线在步骤间隙退出并 markFinished → tryStartNext
        taskScheduler.requestPause(taskId);
        entity.setStatus(VideoTaskStatus.PAUSED.name());
        entity.setCurrentStep("暂停中，等待当前步骤结束…");
        entity.setUpdatedAt(LocalDateTime.now());
        videoTaskMapper.updateById(entity);
        log.info("已请求暂停进行中任务: taskId={}, was={}", taskId, status);
        // 主动尝试调度，即使当前步骤尚未结束，若有其它槽位也可启动排队
        taskScheduler.tryStartNext();
        return toResponse(entity, false);
    }

    /**
     * 失败/暂停任务重试：可重新指定 LLM，重置后重新排队调度。
     */
    public VideoTaskResponse retryTask(Long taskId, VideoRetryRequest request) {
        VideoTaskEntity entity = requireTask(taskId);
        String status = entity.getStatus();
        if (!VideoTaskStatus.FAILED.name().equals(status)
                && !VideoTaskStatus.PAUSED.name().equals(status)) {
            throw new BusinessException(400, "仅失败或已暂停任务可重试，当前状态: " + status);
        }
        if (entity.getSourceUrl() == null || entity.getSourceUrl().isBlank()) {
            throw new BusinessException(400, "任务源链接为空，无法重试");
        }

        // 可选覆盖 LLM
        if (request != null) {
            String p = blankToNull(request.getLlmProvider());
            String m = blankToNull(request.getLlmModel());
            if (p != null) {
                ProviderConfig pc = aiProperties.getProvider(p);
                if (pc == null || pc.getApiKey() == null || pc.getApiKey().isEmpty()) {
                    throw new BusinessException("LLM 供应商不可用或未配置 api-key: " + p);
                }
                entity.setLlmProvider(p);
                if (m == null) {
                    m = aiModelConfigService.firstEnabledModelId(p);
                }
                if (m == null) {
                    throw new BusinessException("未配置可用 LLM 模型，请在「模型管理」中添加");
                }
                entity.setLlmModel(m);
            } else if (m != null) {
                entity.setLlmModel(m);
            }
        }

        storageService.deleteTaskDir(String.valueOf(taskId));
        taskScheduler.clearPauseRequest(taskId);

        entity.setStatus(VideoTaskStatus.PENDING.name());
        entity.setCurrentStep("重试排队中");
        entity.setErrorMessage(null);
        entity.setTitle(null);
        entity.setDurationSeconds(null);
        entity.setVideoPath(null);
        entity.setAudioPath(null);
        entity.setTranscriptionPath(null);
        entity.setSummaryPath(null);
        entity.setTranscriptionJson(null);
        entity.setSummaryJson(null);
        entity.setResultJson(null);
        entity.setDownloadDurationMs(null);
        entity.setTranscribeDurationMs(null);
        entity.setSummarizeDurationMs(null);
        entity.setTotalDurationMs(null);
        entity.setStartedAt(null);
        entity.setFinishedAt(null);
        entity.setUpdatedAt(LocalDateTime.now());
        videoTaskMapper.updateById(entity);

        log.info("任务重试排队: taskId={}, url={}, llm={}/{}",
                taskId, entity.getSourceUrl(), entity.getLlmProvider(), entity.getLlmModel());
        taskScheduler.notifyPending();
        return toResponse(entity, false);
    }

    private String findProviderKey(ProviderConfig target) {
        if (target == null) {
            return null;
        }
        for (Map.Entry<String, ProviderConfig> e : aiProperties.getProviders().entrySet()) {
            if (e.getValue() == target) {
                return e.getKey();
            }
        }
        for (Map.Entry<String, ProviderConfig> e : aiProperties.getProviders().entrySet()) {
            if (e.getValue().getName() != null && e.getValue().getName().equals(target.getName())) {
                return e.getKey();
            }
        }
        return firstAvailableProviderKey();
    }

    private String firstAvailableProviderKey() {
        return aiProperties.getAllAvailableProviders().stream()
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * 查询任务状态与完整结果。
     */
    public VideoTaskResponse getStatus(Long taskId) {
        return toResponse(requireTask(taskId), true);
    }

    /**
     * 分页任务列表（不含完整 result，减轻体积）。
     */
    public VideoTaskPageResponse listTasks(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);

        Page<VideoTaskEntity> mpPage = new Page<>(safePage + 1L, safeSize);
        Page<VideoTaskEntity> result = videoTaskMapper.selectPage(
                mpPage,
                new LambdaQueryWrapper<VideoTaskEntity>()
                        .orderByDesc(VideoTaskEntity::getCreatedAt)
        );

        List<VideoTaskResponse> items = result.getRecords().stream()
                .map(e -> toResponse(e, false))
                .collect(Collectors.toList());

        return VideoTaskPageResponse.builder()
                .items(items)
                .total(result.getTotal())
                .page(safePage)
                .size(safeSize)
                .build();
    }

    /**
     * 最近任务列表（兼容旧接口）。
     */
    public List<VideoTaskResponse> listRecent(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        List<VideoTaskEntity> list = videoTaskMapper.selectList(
                new LambdaQueryWrapper<VideoTaskEntity>()
                        .orderByDesc(VideoTaskEntity::getCreatedAt)
                        .last("LIMIT " + safeLimit)
        );
        return list.stream().map(e -> toResponse(e, false)).collect(Collectors.toList());
    }

    /**
     * 获取带时间戳的转录文字。
     */
    public TranscriptionResult getTranscription(Long taskId) {
        VideoTaskEntity entity = requireTask(taskId);
        if (entity.getTranscriptionJson() == null || entity.getTranscriptionJson().isBlank()) {
            throw new BusinessException(404, "转录结果尚未生成: " + taskId);
        }
        try {
            return objectMapper.readValue(entity.getTranscriptionJson(), TranscriptionResult.class);
        } catch (Exception e) {
            throw new BusinessException("解析转录 JSON 失败: " + e.getMessage());
        }
    }

    /**
     * 获取 AI 核心内容（要点 / 章节 / 思维导图 / repurpose）。
     */
    public VideoSummaryPart getSummary(Long taskId) {
        VideoTaskEntity entity = requireTask(taskId);

        if (entity.getSummaryJson() != null && !entity.getSummaryJson().isBlank()) {
            try {
                return objectMapper.readValue(entity.getSummaryJson(), VideoSummaryPart.class);
            } catch (Exception e) {
                log.warn("解析 summaryJson 失败，尝试从 resultJson 提取: taskId={}", taskId, e);
            }
        }

        if (entity.getResultJson() != null && !entity.getResultJson().isBlank()) {
            try {
                VideoSummaryResponse full = objectMapper.readValue(entity.getResultJson(), VideoSummaryResponse.class);
                if (full.getSummary() != null) {
                    return full.getSummary();
                }
            } catch (Exception e) {
                log.warn("从 resultJson 提取 summary 失败: taskId={}", taskId, e);
            }
        }

        throw new BusinessException(404, "核心内容尚未生成: " + taskId);
    }

    /**
     * 下载原始视频文件流。
     */
    public ResponseEntity<Resource> downloadVideo(Long taskId) {
        VideoTaskEntity entity = requireTask(taskId);
        Path path = storageService.requireExistingFile(entity.getVideoPath(), "视频文件");
        Resource resource = new FileSystemResource(path);
        String contentType = storageService.guessMediaType(path);
        String filename = path.getFileName().toString();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(resource);
    }

    /**
     * 删除视频任务：数据库记录 + 任务目录下视频/音频/转录/摘要等文件。
     */
    public void deleteTask(Long taskId) {
        VideoTaskEntity entity = requireTask(taskId);
        String taskIdStr = String.valueOf(taskId);

        int filesRemoved = storageService.deleteTaskDir(taskIdStr);
        deleteIfExistsQuietly(entity.getVideoPath());
        deleteIfExistsQuietly(entity.getAudioPath());
        deleteIfExistsQuietly(entity.getTranscriptionPath());
        deleteIfExistsQuietly(entity.getSummaryPath());

        int rows = videoTaskMapper.deleteById(taskId);
        if (rows <= 0) {
            throw new BusinessException(404, "任务不存在或已删除: " + taskId);
        }
        log.info("已删除视频任务: taskId={}, title={}, filesRemoved≈{}",
                taskId, entity.getTitle(), filesRemoved);
    }

    private void deleteIfExistsQuietly(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) {
            return;
        }
        try {
            Path p = Path.of(absolutePath);
            if (Files.isRegularFile(p)) {
                Files.deleteIfExists(p);
            }
        } catch (Exception e) {
            log.debug("删除路径忽略: {} — {}", absolutePath, e.getMessage());
        }
    }

    private VideoTaskEntity requireTask(Long taskId) {
        VideoTaskEntity entity = videoTaskMapper.selectById(taskId);
        if (entity == null) {
            throw new BusinessException(404, "任务不存在: " + taskId);
        }
        return entity;
    }

    private VideoTaskResponse toResponse(VideoTaskEntity entity, boolean includeResult) {
        boolean videoAvailable = entity.getVideoPath() != null
                && !entity.getVideoPath().isBlank()
                && Files.isRegularFile(Path.of(entity.getVideoPath()));

        VideoTaskResponse.VideoTaskResponseBuilder builder = VideoTaskResponse.builder()
                .taskId(String.valueOf(entity.getId()))
                .status(entity.getStatus())
                .url(entity.getSourceUrl())
                .title(entity.getTitle())
                .platform(entity.getPlatform())
                .llmProvider(entity.getLlmProvider())
                .llmModel(entity.getLlmModel())
                .currentStep(entity.getCurrentStep())
                .errorMessage(entity.getErrorMessage())
                .durationSeconds(entity.getDurationSeconds())
                .videoAvailable(videoAvailable)
                .videoPath(entity.getVideoPath())
                .audioPath(entity.getAudioPath())
                .createdAt(formatTime(entity.getCreatedAt()))
                .finishedAt(formatTime(entity.getFinishedAt()))
                .startedAt(formatTime(entity.getStartedAt()))
                .downloadDurationMs(entity.getDownloadDurationMs())
                .transcribeDurationMs(entity.getTranscribeDurationMs())
                .summarizeDurationMs(entity.getSummarizeDurationMs())
                .totalDurationMs(entity.getTotalDurationMs());

        if (includeResult
                && VideoTaskStatus.SUCCESS.name().equals(entity.getStatus())
                && entity.getResultJson() != null
                && !entity.getResultJson().isBlank()) {
            try {
                builder.result(objectMapper.readValue(entity.getResultJson(), VideoSummaryResponse.class));
            } catch (Exception e) {
                log.warn("解析任务结果 JSON 失败: taskId={}", entity.getId(), e);
                // 降级：从独立字段组装
                builder.result(buildResultFallback(entity));
            }
        }
        return builder.build();
    }

    private VideoSummaryResponse buildResultFallback(VideoTaskEntity entity) {
        VideoSummaryResponse response = new VideoSummaryResponse();
        response.setVideoId(String.valueOf(entity.getId()));
        response.setTitle(entity.getTitle());
        response.setDuration(entity.getDurationSeconds());
        response.setSourceUrl(entity.getSourceUrl());
        try {
            if (entity.getSummaryJson() != null) {
                response.setSummary(objectMapper.readValue(entity.getSummaryJson(), VideoSummaryPart.class));
            }
            if (entity.getTranscriptionJson() != null) {
                response.setTranscription(objectMapper.readValue(
                        entity.getTranscriptionJson(), TranscriptionResult.class));
            }
        } catch (Exception ignored) {
            // best-effort
        }
        return response;
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? null : time.format(DT_FMT);
    }
}
