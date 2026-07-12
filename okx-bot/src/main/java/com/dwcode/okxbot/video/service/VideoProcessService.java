package com.dwcode.okxbot.video.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.chat.config.AiProperties.ModelConfig;
import com.dwcode.okxbot.chat.config.AiProperties.ProviderConfig;
import com.dwcode.okxbot.video.agent.VideoTaskAsyncRunner;
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
    private final VideoTaskAsyncRunner asyncRunner;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;
    private final VideoProperties videoProperties;
    private final LlmChatClient llmChatClient;

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
            llmProvider = aiProperties.getDefaultProvider() != null
                    ? findProviderKey(aiProperties.getDefaultProvider())
                    : null;
        }
        // 校验供应商与模型
        if (llmProvider != null) {
            ProviderConfig pc = aiProperties.getProvider(llmProvider);
            if (pc == null || pc.getApiKey() == null || pc.getApiKey().isEmpty()) {
                throw new BusinessException("LLM 供应商不可用或未配置 api-key: " + llmProvider);
            }
            if (llmModel == null && pc.getModels() != null && !pc.getModels().isEmpty()) {
                llmModel = pc.getModels().get(0).getId();
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
        asyncRunner.runAsync(entity.getId());

        return toResponse(entity, false);
    }

    /**
     * 可用 LLM 供应商与模型列表（有 api-key 的）。
     */
    public List<Map<String, Object>> listLlmModels() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, ProviderConfig> entry : aiProperties.getAllAvailableProviders()) {
            Map<String, Object> providerMap = new HashMap<>();
            providerMap.put("key", entry.getKey());
            providerMap.put("name", entry.getValue().getName());
            List<Map<String, String>> modelList = new ArrayList<>();
            if (entry.getValue().getModels() != null) {
                for (ModelConfig m : entry.getValue().getModels()) {
                    Map<String, String> mm = new HashMap<>();
                    mm.put("id", m.getId());
                    mm.put("name", m.getName());
                    modelList.add(mm);
                }
            }
            providerMap.put("models", modelList);
            result.add(providerMap);
        }
        return result;
    }

    /**
     * 测试指定模型是否可用。
     */
    public LlmModelTestResponse testLlmModel(LlmModelTestRequest request) {
        return llmChatClient.testModel(request.getProvider().trim(), request.getModel().trim());
    }

    private String findProviderKey(ProviderConfig target) {
        for (Map.Entry<String, ProviderConfig> e : aiProperties.getProviders().entrySet()) {
            if (e.getValue() == target) {
                return e.getKey();
            }
        }
        // 按 name 兜底
        for (Map.Entry<String, ProviderConfig> e : aiProperties.getProviders().entrySet()) {
            if (e.getValue().getName() != null && e.getValue().getName().equals(target.getName())) {
                return e.getKey();
            }
        }
        return aiProperties.getDefaultProvider();
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
                .finishedAt(formatTime(entity.getFinishedAt()));

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
