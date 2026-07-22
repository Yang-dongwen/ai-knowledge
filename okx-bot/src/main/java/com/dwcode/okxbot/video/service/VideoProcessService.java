package com.dwcode.okxbot.video.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.chat.config.AiProperties.ProviderConfig;
import com.dwcode.okxbot.video.agent.VideoTaskScheduler;
import com.dwcode.okxbot.common.ai.AiModelConfigService;
import com.dwcode.okxbot.common.ai.LlmChatClient;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.dto.*;
import com.dwcode.okxbot.video.entity.VideoTaskEntity;
import com.dwcode.okxbot.video.enums.UnderstandingMode;
import com.dwcode.okxbot.video.enums.VideoTaskStatus;
import com.dwcode.okxbot.video.event.VideoTaskEventPublisher;
import com.dwcode.okxbot.video.mapper.VideoTaskMapper;
import com.dwcode.okxbot.video.util.VideoUrlNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.dwcode.okxbot.common.web.MediaRangeSupport;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
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
import java.util.Set;
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
    private final VideoDownloadService downloadService;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;
    private final VideoProperties videoProperties;
    private final LlmChatClient llmChatClient;
    private final AiModelConfigService aiModelConfigService;
    private final VideoTaskEventPublisher eventPublisher;
    private final com.dwcode.okxbot.storage.MediaUrlService mediaUrlService;

    /**
     * 提交处理任务，立即返回 taskId，后台异步执行。
     */
    public VideoTaskResponse submit(VideoProcessRequest request) {
        VideoProcessOptions options = request.getOptions() != null
                ? request.getOptions()
                : new VideoProcessOptions();

        UnderstandingMode mode = UnderstandingMode.from(
                options.getUnderstandingMode() != null
                        ? options.getUnderstandingMode()
                        : videoProperties.getUnderstanding().getMode());

        String llmProvider = null;
        String llmModel = null;
        String omniProvider = null;
        String omniModel = null;

        // 仅下载：不需要 LLM / Omni
        if (mode.needsLlm()) {
            llmProvider = blankToNull(options.getLlmProvider());
            llmModel = blankToNull(options.getLlmModel());
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

            // 画面理解模型：必须由前端选择（库表 capability=video_omni），禁止 yml 写死模型 ID
            omniProvider = blankToNull(options.getOmniProvider());
            omniModel = blankToNull(options.getOmniModel());
            if (mode.needsOmni() && !videoProperties.getUnderstanding().isMock()) {
                if (omniProvider == null || omniModel == null) {
                    throw new BusinessException(400,
                            "混合/仅画面模式请选择「视频理解模型」（capability=video_omni，可在模型管理中配置）");
                }
                var omniCfg = aiModelConfigService.requireEnabledVideoOmniModel(omniProvider, omniModel);
                omniProvider = omniCfg.getProvider();
                omniModel = omniCfg.getModelId();
            }
        }

        VideoTaskEntity entity = new VideoTaskEntity();
        entity.setUserId(SecurityUtils.requireCurrentUserId());
        // 规范化：抖音 user/self?modal_id= → /video/{id}，避免 yt-dlp Unsupported URL
        String sourceUrl = VideoUrlNormalizer.normalize(request.getUrl());
        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new BusinessException(400, "视频链接不能为空");
        }
        entity.setSourceUrl(sourceUrl);
        entity.setPlatform(storageService.detectPlatform(entity.getSourceUrl()));
        entity.setStatus(VideoTaskStatus.PENDING.name());
        entity.setCurrentStep("排队中");
        entity.setLanguage(options.getLanguage() != null ? options.getLanguage() : "zh");
        entity.setUnderstandingMode(mode.wireValue());
        entity.setLlmProvider(llmProvider);
        entity.setLlmModel(llmModel);
        entity.setOmniProvider(omniProvider);
        entity.setOmniModel(omniModel);
        // 仅下载时关闭导图/二创
        if (mode.isDownloadOnly()) {
            entity.setExtractMindMap(0);
            entity.setGenerateRepurposeScript(0);
        } else {
            entity.setExtractMindMap(Boolean.FALSE.equals(options.getExtractMindMap()) ? 0 : 1);
            entity.setGenerateRepurposeScript(Boolean.FALSE.equals(options.getGenerateRepurposeScript()) ? 0 : 1);
        }
        entity.setDegraded(0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        videoTaskMapper.insert(entity);

        log.info("创建视频任务: taskId={}, platform={}, mode={}, llm={}/{}, omni={}/{}, url={}",
                entity.getId(), entity.getPlatform(), mode.wireValue(),
                llmProvider, llmModel, omniProvider, omniModel, entity.getSourceUrl());
        // 进入排队，由调度器按并发槽位启动
        taskScheduler.notifyPending();
        eventPublisher.publishEntity(entity, VideoTaskEventPublisher.TYPE_CREATED);

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
        VideoTaskEntity entity = requireOwnedTask(taskId);
        String status = entity.getStatus();
        if (!VideoTaskStatus.DOWNLOADING.name().equals(status)
                && !VideoTaskStatus.TRANSCRIBING.name().equals(status)
                && !VideoTaskStatus.UNDERSTANDING.name().equals(status)
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
            eventPublisher.publishEntity(entity, VideoTaskEventPublisher.TYPE_STATUS);
            log.info("排队任务已暂停: taskId={}", taskId);
            return toResponse(entity, false);
        }

        // 进行中：发暂停信号，流水线在步骤间隙退出并 markFinished → tryStartNext
        taskScheduler.requestPause(taskId);
        entity.setStatus(VideoTaskStatus.PAUSED.name());
        entity.setCurrentStep("暂停中，等待当前步骤结束…");
        entity.setUpdatedAt(LocalDateTime.now());
        videoTaskMapper.updateById(entity);
        eventPublisher.publishEntity(entity, VideoTaskEventPublisher.TYPE_STATUS);
        log.info("已请求暂停进行中任务: taskId={}, was={}", taskId, status);
        // 主动尝试调度，即使当前步骤尚未结束，若有其它槽位也可启动排队
        taskScheduler.tryStartNext();
        return toResponse(entity, false);
    }

    /** 可重试状态：失败 / 暂停 / 成功（成功=重新提取） */
    private static final Set<String> VIDEO_RETRYABLE_STATUSES = Set.of(
            VideoTaskStatus.FAILED.name(),
            VideoTaskStatus.PAUSED.name(),
            VideoTaskStatus.SUCCESS.name()
    );

    /**
     * 失败 / 暂停 / 成功任务重试：可重新指定 LLM，清空产物后重新排队调度。
     */
    public VideoTaskResponse retryTask(Long taskId, VideoRetryRequest request) {
        VideoTaskEntity entity = requireOwnedTask(taskId);
        String status = entity.getStatus() == null ? "" : entity.getStatus().trim().toUpperCase();
        if (!VIDEO_RETRYABLE_STATUSES.contains(status)) {
            throw new BusinessException(400,
                    "仅失败、已暂停或已成功的任务可重试，当前状态: " + entity.getStatus());
        }
        if (entity.getSourceUrl() == null || entity.getSourceUrl().isBlank()) {
            throw new BusinessException(400, "任务源链接为空，无法重试");
        }

        // 可选覆盖模式 / LLM
        if (request != null) {
            if (request.getUnderstandingMode() != null && !request.getUnderstandingMode().isBlank()) {
                entity.setUnderstandingMode(UnderstandingMode.from(request.getUnderstandingMode()).wireValue());
            }
            UnderstandingMode retryMode = UnderstandingMode.from(entity.getUnderstandingMode());
            if (retryMode.isDownloadOnly()) {
                entity.setLlmProvider(null);
                entity.setLlmModel(null);
                entity.setOmniProvider(null);
                entity.setOmniModel(null);
                entity.setExtractMindMap(0);
                entity.setGenerateRepurposeScript(0);
            } else {
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
                String op = blankToNull(request.getOmniProvider());
                String om = blankToNull(request.getOmniModel());
                if (retryMode.needsOmni() && !videoProperties.getUnderstanding().isMock()) {
                    // 重试画面模式：请求显式指定，或沿用任务上已有模型
                    String useOp = op != null ? op : blankToNull(entity.getOmniProvider());
                    String useOm = om != null ? om : blankToNull(entity.getOmniModel());
                    if (useOp == null || useOm == null) {
                        throw new BusinessException(400,
                                "画面理解模式请选择视频理解模型（capability=video_omni）");
                    }
                    var omniCfg = aiModelConfigService.requireEnabledVideoOmniModel(useOp, useOm);
                    entity.setOmniProvider(omniCfg.getProvider());
                    entity.setOmniModel(omniCfg.getModelId());
                } else {
                    if (op != null) {
                        entity.setOmniProvider(op);
                    }
                    if (om != null) {
                        entity.setOmniModel(om);
                    }
                    if (!retryMode.needsOmni()
                            && request.getUnderstandingMode() != null
                            && !request.getUnderstandingMode().isBlank()) {
                        // 切回仅音频/仅下载时清空 omni
                        entity.setOmniProvider(null);
                        entity.setOmniModel(null);
                    }
                }
            }
        }

        storageService.deleteTaskStorage(entity.getUserId(), String.valueOf(taskId));
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
        entity.setVisualPath(null);
        entity.setTranscriptionJson(null);
        entity.setSummaryJson(null);
        entity.setVisualJson(null);
        entity.setResultJson(null);
        entity.setDownloadDurationMs(null);
        entity.setTranscribeDurationMs(null);
        entity.setUnderstandDurationMs(null);
        entity.setSummarizeDurationMs(null);
        entity.setTotalDurationMs(null);
        entity.setDegraded(0);
        entity.setDegradeReason(null);
        entity.setStartedAt(null);
        entity.setFinishedAt(null);
        entity.setUpdatedAt(LocalDateTime.now());
        videoTaskMapper.updateById(entity);
        eventPublisher.publishEntity(entity, VideoTaskEventPublisher.TYPE_STATUS);

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
        return toResponse(requireOwnedTask(taskId), true);
    }

    /**
     * 分页任务列表（不含完整 result，减轻体积）。
     */
    public VideoTaskPageResponse listTasks(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Long userId = SecurityUtils.requireCurrentUserId();

        Page<VideoTaskEntity> mpPage = new Page<>(safePage + 1L, safeSize);
        Page<VideoTaskEntity> result = videoTaskMapper.selectPage(
                mpPage,
                new LambdaQueryWrapper<VideoTaskEntity>()
                        .eq(VideoTaskEntity::getUserId, userId)
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
        Long userId = SecurityUtils.requireCurrentUserId();
        List<VideoTaskEntity> list = videoTaskMapper.selectList(
                new LambdaQueryWrapper<VideoTaskEntity>()
                        .eq(VideoTaskEntity::getUserId, userId)
                        .orderByDesc(VideoTaskEntity::getCreatedAt)
                        .last("LIMIT " + safeLimit)
        );
        return list.stream().map(e -> toResponse(e, false)).collect(Collectors.toList());
    }

    /**
     * 获取带时间戳的转录文字。
     */
    public TranscriptionResult getTranscription(Long taskId) {
        VideoTaskEntity entity = requireOwnedTask(taskId);
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
        VideoTaskEntity entity = requireOwnedTask(taskId);

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
     * PR5：返回可直链播放/下载的 URL（R2 预签名优先，失败或 local 回退代理路径）。
     *
     * @param disposition inline（播放）或 attachment（下载）
     */
    public com.dwcode.okxbot.storage.dto.MediaUrlResponse resolveVideoMediaUrl(Long taskId, String disposition) {
        VideoTaskEntity entity = requireOwnedTask(taskId);
        ResolvedMedia loc = resolvePlayableVideo(entity, taskId);
        boolean attachment = disposition != null && disposition.equalsIgnoreCase("attachment");
        String proxyPath = "/api/v1/video/tasks/" + taskId + "/video";
        String downloadName = loc.filename() != null ? loc.filename() : "video.mp4";
        return mediaUrlService.resolve(loc.keyOrPath(), proxyPath, attachment, downloadName);
    }

    /**
     * 下载/播放视频流（支持本地绝对路径或对象存储 key）。
     * <p>支持 HTTP Range（206），浏览器可边下边播、拖动进度条。
     * <p>HEVC 等不友好编码会懒转 H.264 并回写 browser 对象。
     * <p>PR5 起播放/下载优先走 {@link #resolveVideoMediaUrl} 直连 R2；本接口作回退代理。
     *
     * @param rangeHeader 请求头 {@code Range}，可为 null
     */
    public ResponseEntity<Resource> downloadVideo(Long taskId, String rangeHeader) {
        VideoTaskEntity entity = requireOwnedTask(taskId);
        ResolvedMedia loc = resolvePlayableVideo(entity, taskId);
        String streamKeyOrPath = loc.keyOrPath();
        String filename = loc.filename() != null ? loc.filename() : "video.mp4";
        long len = loc.sizeBytes();

        if (com.dwcode.okxbot.storage.ObjectKeyBuilder.looksLikeLocalAbsolutePath(streamKeyOrPath)) {
            final Path playPath = Path.of(streamKeyOrPath);
            String ct = filename.toLowerCase().endsWith(".webm") ? "video/webm" : "video/mp4";
            return MediaRangeSupport.build(rangeHeader, len, ct, filename, (start, end) -> {
                try {
                    long size = Files.size(playPath);
                    long s = Math.max(0, start);
                    long e = Math.min(end, size - 1);
                    var in = Files.newInputStream(playPath);
                    if (s > 0) {
                        in.skipNBytes(s);
                    }
                    return new com.dwcode.okxbot.common.web.LimitedInputStream(in, e - s + 1);
                } catch (Exception ex) {
                    throw new BusinessException("读取本地视频失败: " + ex.getMessage());
                }
            });
        }

        final String streamLoc = streamKeyOrPath;
        String ct = filename.toLowerCase().endsWith(".webm") ? "video/webm" : "video/mp4";
        return MediaRangeSupport.build(rangeHeader, len, ct, filename,
                (start, end) -> storageService.openMediaStream(streamLoc, start, end));
    }

    private record ResolvedMedia(String keyOrPath, String filename, long sizeBytes) {
    }

    /**
     * 解析可播位置：本地 browser 优先；对象存储 browser key 优先；必要时懒转码并回写。
     */
    private ResolvedMedia resolvePlayableVideo(VideoTaskEntity entity, Long taskId) {
        String loc = entity.getVideoPath();
        if (loc == null || loc.isBlank()) {
            throw new BusinessException(404, "视频路径为空");
        }
        String streamKeyOrPath = loc;
        String filename = "video.mp4";
        long len = 0L;

        if (com.dwcode.okxbot.storage.ObjectKeyBuilder.looksLikeLocalAbsolutePath(loc)) {
            Path path = storageService.requireExistingFile(loc, "视频文件");
            try {
                path = downloadService.ensureBrowserPlayable(path);
            } catch (Exception e) {
                log.warn("浏览器可播转码跳过: taskId={}, err={}", taskId, e.getMessage());
            }
            filename = path.getFileName() != null ? path.getFileName().toString() : "video.mp4";
            try {
                len = Files.size(path);
            } catch (Exception ignored) {
                // ignore
            }
            return new ResolvedMedia(path.toAbsolutePath().toString(), filename, len);
        }

        String browserKey = siblingBrowserObjectKey(loc);
        try {
            if (browserKey != null && storageService.objectStorage().exists(browserKey)) {
                streamKeyOrPath = browserKey;
            } else if (!storageService.objectStorage().exists(loc)) {
                throw new BusinessException(404, "视频对象不存在: " + loc);
            } else if (browserKey != null && loc.endsWith(".mp4") && !loc.contains("browser")) {
                String tid = String.valueOf(taskId);
                try {
                    Path local = storageService.materializeToScratch(loc, tid, "video.source.mp4");
                    Path playable = downloadService.ensureBrowserPlayable(local);
                    if (playable != null && Files.isRegularFile(playable)
                            && playable.getFileName() != null
                            && playable.getFileName().toString().contains("browser")) {
                        String key = storageService.publishFile(
                                StorageService.effectiveUserId(entity),
                                tid,
                                playable,
                                "video.browser.mp4");
                        streamKeyOrPath = key;
                        entity.setVideoPath(key);
                        entity.setUpdatedAt(LocalDateTime.now());
                        videoTaskMapper.updateById(entity);
                    }
                } catch (Exception e) {
                    log.warn("对象视频懒转码失败，回退原 key: taskId={} — {}", taskId, e.getMessage());
                } finally {
                    try {
                        storageService.cleanupScratchOnly(tid);
                    } catch (Exception cleanEx) {
                        log.warn("懒转码后清理 scratch 失败: taskId={} — {}", taskId, cleanEx.getMessage());
                    }
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("读取视频对象失败: " + e.getMessage());
        }

        var meta = storageService.headMedia(streamKeyOrPath);
        if (meta.isPresent()) {
            len = meta.get().getSizeBytes();
            int slash = streamKeyOrPath.lastIndexOf('/');
            filename = slash >= 0 ? streamKeyOrPath.substring(slash + 1) : streamKeyOrPath;
        }
        return new ResolvedMedia(streamKeyOrPath, filename, len);
    }

    private static String siblingBrowserObjectKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        int slash = key.lastIndexOf('/');
        String name = slash >= 0 ? key.substring(slash + 1) : key;
        String prefix = slash >= 0 ? key.substring(0, slash + 1) : "";
        if ("video.browser.mp4".equals(name)) {
            return key;
        }
        if (name.startsWith("video.")) {
            return prefix + "video.browser.mp4";
        }
        return null;
    }

    /**
     * 删除视频任务：数据库记录 + scratch/对象存储前缀。
     */
    public void deleteTask(Long taskId) {
        VideoTaskEntity entity = requireOwnedTask(taskId);
        Long ownerId = entity.getUserId();
        String taskIdStr = String.valueOf(taskId);

        int filesRemoved = storageService.deleteTaskStorage(ownerId, taskIdStr);

        int rows = videoTaskMapper.deleteById(taskId);
        if (rows <= 0) {
            throw new BusinessException(404, "任务不存在或已删除: " + taskId);
        }
        eventPublisher.publishDeleted(ownerId, taskId);
        log.info("已删除视频任务: taskId={}, title={}, storageRemoved≈{}",
                taskId, entity.getTitle(), filesRemoved);
    }

    private VideoTaskEntity requireOwnedTask(Long taskId) {
        VideoTaskEntity entity = videoTaskMapper.selectById(taskId);
        if (entity == null) {
            throw new BusinessException(404, "任务不存在: " + taskId);
        }
        Long userId = SecurityUtils.requireCurrentUserId();
        // 兼容历史数据 user_id 为空：仅允许本人或未绑定数据在登录后不可见他人
        if (entity.getUserId() != null && !entity.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问该任务");
        }
        if (entity.getUserId() == null) {
            // 旧数据首次访问归属当前用户
            entity.setUserId(userId);
            entity.setUpdatedAt(LocalDateTime.now());
            videoTaskMapper.updateById(entity);
        }
        return entity;
    }

    private VideoTaskResponse toResponse(VideoTaskEntity entity, boolean includeResult) {
        boolean videoAvailable = storageService.mediaAvailable(entity.getVideoPath());

        VideoTaskResponse.VideoTaskResponseBuilder builder = VideoTaskResponse.builder()
                .taskId(String.valueOf(entity.getId()))
                .status(entity.getStatus())
                .url(entity.getSourceUrl())
                .title(entity.getTitle())
                .platform(entity.getPlatform())
                .llmProvider(entity.getLlmProvider())
                .llmModel(entity.getLlmModel())
                .understandingMode(entity.getUnderstandingMode())
                .omniProvider(entity.getOmniProvider())
                .omniModel(entity.getOmniModel())
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
                .understandDurationMs(entity.getUnderstandDurationMs())
                .summarizeDurationMs(entity.getSummarizeDurationMs())
                .totalDurationMs(entity.getTotalDurationMs())
                .degraded(entity.getDegraded() != null && entity.getDegraded() == 1)
                .degradeReason(entity.getDegradeReason());

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
