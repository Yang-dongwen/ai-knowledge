package com.dwcode.okxbot.aigen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dwcode.okxbot.aigen.agent.AigenTaskScheduler;
import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.domain.shot.ShotDto;
import com.dwcode.okxbot.aigen.domain.shot.ShotlistDto;
import com.dwcode.okxbot.aigen.dto.*;
import com.dwcode.okxbot.aigen.entity.AigenTaskEntity;
import com.dwcode.okxbot.aigen.enums.AigenTaskStatus;
import com.dwcode.okxbot.aigen.event.AigenTaskEventPublisher;
import com.dwcode.okxbot.aigen.mapper.AigenTaskMapper;
import com.dwcode.okxbot.aigen.port.RenderCommand;
import com.dwcode.okxbot.aigen.port.RenderResult;
import com.dwcode.okxbot.aigen.port.VideoRenderPort;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.chat.config.AiProperties.ProviderConfig;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.member.service.MemberStatusService;
import com.dwcode.okxbot.imggen.util.AspectRatioMapper;
import com.dwcode.okxbot.storage.ObjectKeyBuilder;
import com.dwcode.okxbot.common.ai.AiModelConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 视频生成任务业务入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AigenTaskService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> ALLOWED_ASPECT = Set.of("9:16", "16:9", "1:1");

    private final AigenTaskMapper aigenTaskMapper;
    private final AigenTaskScheduler taskScheduler;
    private final AigenTaskEventPublisher eventPublisher;
    private final AigenStorageService storageService;
    private final AigenProperties aigenProperties;
    private final ObjectMapper objectMapper;
    private final TemplateRegistry templateRegistry;
    private final AiProperties aiProperties;
    private final AiModelConfigService aiModelConfigService;
    private final VisualShotAssetService visualShotAssetService;
    private final VideoRenderPort videoRenderPort;
    private final com.dwcode.okxbot.storage.MediaUrlService mediaUrlService;
    private final MemberStatusService memberStatusService;

    public AigenTaskResponse create(AigenCreateRequest request) {
        memberStatusService.requireActiveMember();
        Long userId = SecurityUtils.requireCurrentUserId();
        int perUser = Math.max(1, aigenProperties.getMaxConcurrentTasksPerUser());
        if (countUserInFlight(userId) >= perUser) {
            throw new BusinessException(429, "并发 AI 视频任务数已达上限（" + perUser + "），请等待完成后再提交");
        }
        String prompt = request.getPrompt().trim();
        if (prompt.isEmpty()) {
            throw new BusinessException(400, "prompt 不能为空");
        }

        AigenCreateOptions options = request.getOptions() != null
                ? request.getOptions()
                : new AigenCreateOptions();

        String pipelineMode = blankToNull(options.getPipelineMode());
        if (pipelineMode == null) {
            pipelineMode = blankToNull(aigenProperties.getDefaultPipelineMode());
        }
        if (pipelineMode == null) {
            pipelineMode = "visual";
        }
        pipelineMode = pipelineMode.toLowerCase();
        if (!"visual".equals(pipelineMode) && !"template".equals(pipelineMode)) {
            throw new BusinessException(400, "pipelineMode 仅支持 visual / template");
        }

        String templateId = blankToNull(request.getTemplateId());
        if ("visual".equals(pipelineMode)) {
            if (templateId == null) {
                templateId = TemplateRegistry.VISUAL_TIMELINE;
            }
        } else {
            if (templateId == null) {
                templateId = TemplateRegistry.KNOWLEDGE_CARDS;
            }
            if (!templateRegistry.exists(templateId)) {
                throw new BusinessException(400, "不支持的模板: " + templateId);
            }
        }

        String audioMode = blankToNull(options.getAudioMode());
        if (audioMode == null) {
            audioMode = aigenProperties.getVisual().getDefaultAudioMode();
        }
        if (audioMode == null) {
            audioMode = "bgm_only";
        }
        audioMode = audioMode.toLowerCase();
        if (!Set.of("none", "bgm_only", "tts", "tts_bgm").contains(audioMode)) {
            throw new BusinessException(400, "audioMode 仅支持 none / bgm_only / tts / tts_bgm");
        }
        // 诚实失败：需要 BGM 却没有文件时，创建即拒绝（避免渲完才失败）
        if (("bgm_only".equals(audioMode) || "tts_bgm".equals(audioMode))
                && aigenProperties.getVisual().isRequireBgmWhenRequested()
                && !aigenProperties.isMockPipeline()
                && !"mock".equalsIgnoreCase(aigenProperties.getSteps().getAsset())
                && !hasConfiguredBgmFile()) {
            throw new BusinessException(400,
                    "当前 audioMode=" + audioMode + " 需要 BGM，但 aigen.visual.bgm-dir 下无 mp3/wav。"
                            + "请放入背景音乐，或改 audioMode=none/tts，或关闭 require-bgm-when-requested");
        }

        String stylePreset = blankToNull(options.getStylePreset());
        if (stylePreset == null) {
            stylePreset = aigenProperties.getVisual().getDefaultStylePreset();
        }

        String aspect = blankToNull(options.getAspectRatio());
        if (aspect == null) {
            aspect = "9:16";
        }
        if (!ALLOWED_ASPECT.contains(aspect)) {
            throw new BusinessException(400, "aspectRatio 仅支持 9:16 / 16:9 / 1:1");
        }

        int minD = aigenProperties.getMinDurationSec();
        int maxD = aigenProperties.getMaxDurationSec();
        int duration = options.getTargetDurationSec() != null ? options.getTargetDurationSec() : 30;
        if (duration < minD || duration > maxD) {
            throw new BusinessException(400, "targetDurationSec 需在 " + minD + "～" + maxD + " 秒");
        }

        String language = blankToNull(options.getLanguage());
        if (language == null || "auto".equalsIgnoreCase(language)) {
            // 按用户提示词粗略判定，后续分镜/口播/画面 prompt 均跟随该语言
            language = detectLanguageFromPrompt(prompt);
        }

        String llmProvider = blankToNull(options.getLlmProvider());
        if (llmProvider == null) {
            llmProvider = blankToNull(aigenProperties.getLlm().getProvider());
        }
        if (llmProvider == null) {
            ProviderConfig def = aiProperties.getDefaultProvider();
            if (def != null) {
                for (Map.Entry<String, ProviderConfig> e : aiProperties.getProviders().entrySet()) {
                    if (e.getValue() == def || (e.getValue().getName() != null
                            && e.getValue().getName().equals(def.getName()))) {
                        llmProvider = e.getKey();
                        break;
                    }
                }
            }
        }
        if (llmProvider == null && !aiProperties.getAllAvailableProviders().isEmpty()) {
            llmProvider = aiProperties.getAllAvailableProviders().get(0).getKey();
        }
        String llmModel = blankToNull(options.getLlmModel());
        if (llmModel == null) {
            llmModel = blankToNull(aigenProperties.getLlm().getModel());
        }

        boolean needRealPlan = !aigenProperties.isMockPipeline()
                && !"mock".equalsIgnoreCase(aigenProperties.getSteps().getPlan());
        if (needRealPlan) {
            if (llmProvider == null) {
                throw new BusinessException(400, "未配置 LLM 供应商，请在 ai.providers 配置 api-key");
            }
            ProviderConfig pc = aiProperties.getProvider(llmProvider);
            if (pc == null || pc.getApiKey() == null || pc.getApiKey().isBlank()) {
                throw new BusinessException(400, "LLM 供应商不可用或未配置 api-key: " + llmProvider);
            }
            if (llmModel == null) {
                llmModel = aiModelConfigService.firstEnabledModelId(llmProvider);
            }
            if (llmModel == null) {
                throw new BusinessException(400, "未配置可用 LLM 模型，请在「模型管理」中添加");
            }
        }

        // visual 出图模型（与文生图共用 capability=image；默认高质量 FLUX.1-dev）
        String imageProvider = blankToNull(options.getImageProvider());
        String imageModel = blankToNull(options.getImageModel());
        if (imageModel == null && aigenProperties.getVisual() != null) {
            imageModel = blankToNull(aigenProperties.getVisual().getDefaultImageModel());
        }
        if (imageProvider == null && aigenProperties.getVisual() != null) {
            imageProvider = blankToNull(aigenProperties.getVisual().getImageProviderKey());
        }
        boolean needRealImage = "visual".equals(pipelineMode)
                && !aigenProperties.isMockPipeline()
                && !"mock".equalsIgnoreCase(aigenProperties.getSteps().getAsset());
        if (needRealImage) {
            try {
                var imgCfg = aiModelConfigService.requireEnabledImageModel(imageProvider, imageModel);
                imageProvider = imgCfg.getProvider();
                imageModel = imgCfg.getModelId();
            } catch (BusinessException ex) {
                // 默认 dev 未入库时回退到库表排序第一（多为 schnell）
                if (imageModel != null) {
                    log.warn("默认生图模型不可用 [{}]，回退库表首选: {}", imageModel, ex.getMessage());
                    var imgCfg = aiModelConfigService.requireEnabledImageModel(imageProvider, null);
                    imageProvider = imgCfg.getProvider();
                    imageModel = imgCfg.getModelId();
                } else {
                    throw ex;
                }
            }
        } else if ("visual".equals(pipelineMode)) {
            // mock 出图：允许空，落库占位
            if (imageProvider == null) {
                imageProvider = blankToNull(aigenProperties.getVisual().getImageProviderKey());
            }
            if (imageProvider == null) {
                imageProvider = "nvidia";
            }
            if (imageModel == null) {
                imageModel = "mock-image";
            }
        }

        AigenTaskEntity entity = new AigenTaskEntity();
        entity.setUserId(SecurityUtils.requireCurrentUserId());
        entity.setPrompt(prompt);
        entity.setTitle(deriveTitle(prompt));
        entity.setNegativePrompt(blankToNull(options.getNegativePrompt()));
        entity.setTemplateId(templateId);
        entity.setPipelineMode(pipelineMode);
        entity.setAudioMode(audioMode);
        entity.setStylePreset(stylePreset);
        entity.setShotCount(0);
        entity.setAssetDoneCount(0);
        entity.setStatus(AigenTaskStatus.PENDING.name());
        entity.setCurrentStep("排队中");
        entity.setProgress(0);
        entity.setLanguage(language);
        entity.setAspectRatio(aspect);
        entity.setTargetDurationSec(duration);
        entity.setVoiceId(blankToNull(options.getVoiceId()));
        entity.setBgmId(blankToNull(options.getBgmId()));
        entity.setStyleJson(blankToNull(options.getStyleJson()));
        entity.setLlmProvider(llmProvider);
        entity.setLlmModel(llmModel);
        entity.setImageProvider(imageProvider);
        entity.setImageModel(imageModel);
        boolean enhanceImg = Boolean.TRUE.equals(options.getEnhanceImagePrompt())
                || (options.getEnhanceImagePrompt() == null
                && aigenProperties.getVisual().isDefaultEnhanceImagePrompt());
        entity.setEnhanceImagePrompt(enhanceImg ? 1 : 0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        aigenTaskMapper.insert(entity);

        log.info("创建 aigen 任务: id={}, mode={}, template={}, audio={}, duration={}s, llm={}/{}, image={}/{}",
                entity.getId(), pipelineMode, templateId, audioMode, duration,
                llmProvider, llmModel, imageProvider, imageModel);
        taskScheduler.notifyPending();
        eventPublisher.publishEntity(entity, AigenTaskEventPublisher.TYPE_CREATED);
        return toResponse(entity);
    }

    public AigenTaskResponse getTask(Long taskId) {
        return toResponse(requireOwnedTask(taskId));
    }

    public AigenTaskPageResponse listTasks(int page, int size, String status) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Long userId = SecurityUtils.requireCurrentUserId();

        LambdaQueryWrapper<AigenTaskEntity> q = new LambdaQueryWrapper<AigenTaskEntity>()
                .eq(AigenTaskEntity::getUserId, userId)
                .orderByDesc(AigenTaskEntity::getCreatedAt);
        if (status != null && !status.isBlank()) {
            AigenTaskStatus st = AigenTaskStatus.from(status);
            if (st == null) {
                throw new BusinessException(400, "无效 status: " + status);
            }
            q.eq(AigenTaskEntity::getStatus, st.name());
        }

        Page<AigenTaskEntity> mpPage = new Page<>(safePage + 1L, safeSize);
        Page<AigenTaskEntity> result = aigenTaskMapper.selectPage(mpPage, q);

        List<AigenTaskResponse> items = result.getRecords().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return AigenTaskPageResponse.builder()
                .items(items)
                .total(result.getTotal())
                .page(safePage)
                .size(safeSize)
                .build();
    }

    public AigenTaskResponse cancelTask(Long taskId) {
        AigenTaskEntity entity = requireOwnedTask(taskId);
        String status = entity.getStatus();
        if (AigenTaskStatus.SUCCESS.name().equals(status)
                || AigenTaskStatus.FAILED.name().equals(status)
                || AigenTaskStatus.CANCELLED.name().equals(status)
                || AigenTaskStatus.PAUSED.name().equals(status)) {
            throw new BusinessException(400, "当前状态不可取消: " + status);
        }

        if (AigenTaskStatus.PENDING.name().equals(status)) {
            entity.setStatus(AigenTaskStatus.CANCELLED.name());
            entity.setCurrentStep("已取消（未开始）");
            entity.setErrorMessage("");
            entity.setFinishedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            aigenTaskMapper.updateById(entity);
            taskScheduler.markFinished(taskId);
            eventPublisher.publishEntity(entity, AigenTaskEventPublisher.TYPE_STATUS);
            return toResponse(entity);
        }

        taskScheduler.requestCancel(taskId);
        entity.setCurrentStep("取消中，等待当前步骤结束…");
        entity.setUpdatedAt(LocalDateTime.now());
        aigenTaskMapper.updateById(entity);
        eventPublisher.publishEntity(entity, AigenTaskEventPublisher.TYPE_STATUS);
        taskScheduler.tryStartNext();
        return toResponse(entity);
    }

    /**
     * 暂停：排队中立即暂停；进行中在步骤边界协作式中断（规划/素材/渲染之间）。
     */
    public AigenTaskResponse pauseTask(Long taskId) {
        AigenTaskEntity entity = requireOwnedTask(taskId);
        String status = entity.getStatus();
        if (!AigenTaskStatus.PENDING.name().equals(status)
                && !AigenTaskStatus.PLANNING.name().equals(status)
                && !AigenTaskStatus.ASSET_GENERATING.name().equals(status)
                && !AigenTaskStatus.RENDERING.name().equals(status)) {
            throw new BusinessException(400, "仅排队中或进行中的任务可暂停，当前: " + status);
        }

        if (AigenTaskStatus.PENDING.name().equals(status)) {
            entity.setStatus(AigenTaskStatus.PAUSED.name());
            entity.setCurrentStep("已暂停（未开始执行）");
            entity.setErrorMessage("");
            entity.setFinishedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            aigenTaskMapper.updateById(entity);
            taskScheduler.markFinished(taskId);
            eventPublisher.publishEntity(entity, AigenTaskEventPublisher.TYPE_STATUS);
            log.info("排队任务已暂停: taskId={}", taskId);
            return toResponse(entity);
        }

        taskScheduler.requestPause(taskId);
        entity.setStatus(AigenTaskStatus.PAUSED.name());
        entity.setCurrentStep("暂停中，等待当前步骤结束…");
        entity.setErrorMessage("");
        entity.setUpdatedAt(LocalDateTime.now());
        aigenTaskMapper.updateById(entity);
        eventPublisher.publishEntity(entity, AigenTaskEventPublisher.TYPE_STATUS);
        log.info("已请求暂停进行中任务: taskId={}, was={}", taskId, status);
        taskScheduler.tryStartNext();
        return toResponse(entity);
    }

    /** 可重试状态：失败 / 取消 / 暂停 / 成功（成功=重新生成） */
    private static final Set<String> AIGEN_RETRYABLE_STATUSES = Set.of(
            AigenTaskStatus.FAILED.name(),
            AigenTaskStatus.CANCELLED.name(),
            AigenTaskStatus.PAUSED.name(),
            AigenTaskStatus.SUCCESS.name()
    );

    /**
     * 失败 / 取消 / 暂停 / 成功 任务重试。
     * 可覆盖 LLM；清空分镜与成片产物后重新排队。
     */
    public AigenTaskResponse retryTask(Long taskId, AigenRetryRequest request) {
        memberStatusService.requireActiveMember();
        AigenTaskEntity entity = requireOwnedTask(taskId);
        String status = entity.getStatus() == null ? "" : entity.getStatus().trim().toUpperCase();
        if (!AIGEN_RETRYABLE_STATUSES.contains(status)) {
            throw new BusinessException(400,
                    "仅失败、已取消、已暂停或已成功任务可重试，当前: " + entity.getStatus());
        }

        // 可选覆盖 LLM（与创建任务同一套校验）
        if (request != null) {
            String p = blankToNull(request.getLlmProvider());
            String m = blankToNull(request.getLlmModel());
            if (p != null) {
                ProviderConfig pc = aiProperties.getProvider(p);
                if (pc == null || pc.getApiKey() == null || pc.getApiKey().isEmpty()) {
                    throw new BusinessException(400, "LLM 供应商不可用或未配置 api-key: " + p);
                }
                entity.setLlmProvider(p);
                if (m == null) {
                    m = aiModelConfigService.firstEnabledModelId(p);
                }
                if (m == null) {
                    throw new BusinessException(400, "未配置可用 LLM 模型，请在「模型管理」中添加");
                }
                entity.setLlmModel(m);
            } else if (m != null) {
                entity.setLlmModel(m);
            }

            // visual 出图模型覆盖
            String ip = blankToNull(request.getImageProvider());
            String im = blankToNull(request.getImageModel());
            if (ip != null || im != null) {
                boolean needRealImage = "visual".equalsIgnoreCase(entity.getPipelineMode())
                        && !aigenProperties.isMockPipeline()
                        && !"mock".equalsIgnoreCase(aigenProperties.getSteps().getAsset());
                if (needRealImage) {
                    String useP = ip != null ? ip : entity.getImageProvider();
                    String useM = im != null ? im : entity.getImageModel();
                    var imgCfg = aiModelConfigService.requireEnabledImageModel(useP, useM);
                    entity.setImageProvider(imgCfg.getProvider());
                    entity.setImageModel(imgCfg.getModelId());
                } else {
                    if (ip != null) {
                        entity.setImageProvider(ip);
                    }
                    if (im != null) {
                        entity.setImageModel(im);
                    }
                }
            }
        }

        // 清空 scratch / 对象前缀，避免成功任务重跑残留
        try {
            storageService.deleteTaskStorage(entity.getUserId(), String.valueOf(taskId));
        } catch (Exception e) {
            log.warn("重试前清理任务存储失败 taskId={}: {}", taskId, e.getMessage());
        }

        taskScheduler.clearCancelRequest(taskId);
        taskScheduler.clearPauseRequest(taskId);
        entity.setStatus(AigenTaskStatus.PENDING.name());
        entity.setCurrentStep("重试排队中");
        entity.setProgress(0);
        // 空串确保序列化带上 errorMessage，前端可清掉旧错误
        entity.setErrorMessage("");
        entity.setStoryboardJson(null);
        entity.setStoryboardPath(null);
        entity.setOutputPath(null);
        entity.setPosterPath(null);
        entity.setOutputSizeBytes(null);
        entity.setPlanDurationMs(null);
        entity.setAssetDurationMs(null);
        entity.setRenderDurationMs(null);
        entity.setTotalDurationMs(null);
        entity.setStartedAt(null);
        entity.setFinishedAt(null);
        entity.setUpdatedAt(LocalDateTime.now());
        aigenTaskMapper.updateById(entity);
        eventPublisher.publishEntity(entity, AigenTaskEventPublisher.TYPE_STATUS);
        log.info("aigen 任务重试排队: id={}, statusWas={}, llm={}/{}",
                taskId, status, entity.getLlmProvider(), entity.getLlmModel());
        taskScheduler.notifyPending();
        return toResponse(entity);
    }

    public void deleteTask(Long taskId) {
        AigenTaskEntity entity = requireOwnedTask(taskId);
        Long ownerId = entity.getUserId();
        String status = entity.getStatus();
        if (AigenTaskStatus.PLANNING.name().equals(status)
                || AigenTaskStatus.ASSET_GENERATING.name().equals(status)
                || AigenTaskStatus.RENDERING.name().equals(status)
                || AigenTaskStatus.PENDING.name().equals(status)) {
            taskScheduler.requestCancel(taskId);
            taskScheduler.requestPause(taskId);
        }

        if (aigenProperties.isCleanupOnDelete()) {
            storageService.deleteTaskStorage(ownerId, String.valueOf(taskId));
        }

        int rows = aigenTaskMapper.deleteById(taskId);
        if (rows <= 0) {
            throw new BusinessException(404, "任务不存在或已删除: " + taskId);
        }
        taskScheduler.markFinished(taskId);
        eventPublisher.publishDeleted(ownerId, taskId);
        log.info("已删除 aigen 任务: taskId={}", taskId);
    }

    public List<AigenTemplateResponse> listTemplates() {
        return templateRegistry.listAll();
    }

    /**
     * 常用音色列表（Edge 语音 ID；Windows SAPI 会忽略具体 ID 用系统默认）。
     */
    public List<Map<String, String>> listVoices() {
        return List.of(
                Map.of("id", "zh-CN-XiaoxiaoNeural", "name", "晓晓（女·推荐）", "lang", "zh"),
                Map.of("id", "zh-CN-YunxiNeural", "name", "云希（男）", "lang", "zh"),
                Map.of("id", "zh-CN-YunyangNeural", "name", "云扬（男·播报）", "lang", "zh"),
                Map.of("id", "zh-CN-XiaoyiNeural", "name", "晓伊（女）", "lang", "zh"),
                Map.of("id", "zh-CN-XiaochenNeural", "name", "晓辰（女）", "lang", "zh"),
                Map.of("id", "en-US-JennyNeural", "name", "Jenny (EN Female)", "lang", "en"),
                Map.of("id", "en-US-GuyNeural", "name", "Guy (EN Male)", "lang", "en")
        );
    }

    /**
     * PR5：成片直链（R2 预签名优先）。
     */
    public com.dwcode.okxbot.storage.dto.MediaUrlResponse resolveOutputMediaUrl(Long taskId, String disposition) {
        AigenTaskEntity entity = requireOwnedTask(taskId);
        String keyOrPath = storageService.resolveOutputKeyOrPath(entity);
        boolean attachment = disposition != null && disposition.equalsIgnoreCase("attachment");
        return mediaUrlService.resolve(
                keyOrPath,
                "/api/v1/aigen/tasks/" + taskId + "/media/output",
                attachment,
                "output.mp4");
    }

    /**
     * PR5：镜头预览图直链。
     */
    public com.dwcode.okxbot.storage.dto.MediaUrlResponse resolveShotImageMediaUrl(Long taskId, String shotId) {
        AigenTaskEntity entity = requireOwnedTask(taskId);
        ShotlistDto list = loadShotlist(entity);
        ShotDto shot = findShot(list, shotId);
        Path workDir = resolveWorkDir(entity);
        Path path = resolveShotPreviewImage(workDir, shot);
        String keyOrPath;
        String fileName = "shot.jpg";
        String rel = null;
        if (path != null && Files.isRegularFile(path)) {
            keyOrPath = path.toAbsolutePath().toString();
            fileName = path.getFileName().toString();
            try {
                rel = workDir.toAbsolutePath().normalize()
                        .relativize(path.toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
            } catch (Exception ignored) {
                // ignore
            }
        } else {
            rel = resolveShotPreviewRelative(shot);
            if (rel == null) {
                throw new BusinessException(404, "镜头图片不存在（仅有视频或文件缺失）");
            }
            keyOrPath = storageService.resolveRelativeKeyOrPath(entity, rel);
            if (keyOrPath == null) {
                throw new BusinessException(404, "镜头图片不存在（本地已清理且对象存储无此文件）");
            }
            fileName = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
        }
        // 若本地路径已失效但 R2 有对象，改用 object key 做 presign
        if (ObjectKeyBuilder.looksLikeLocalAbsolutePath(keyOrPath)
                && !Files.isRegularFile(Path.of(keyOrPath))
                && rel != null) {
            String obj = storageService.resolveRelativeKeyOrPath(entity, rel);
            if (obj != null && !ObjectKeyBuilder.looksLikeLocalAbsolutePath(obj)) {
                keyOrPath = obj;
            }
        }
        String proxy = "/api/v1/aigen/tasks/" + taskId + "/shots/"
                + java.net.URLEncoder.encode(shotId, java.nio.charset.StandardCharsets.UTF_8) + "/image";
        return mediaUrlService.resolve(keyOrPath, proxy, false, fileName);
    }

    /**
     * 鉴权后流式输出成片（本地路径或对象存储 key），支持 HTTP Range 边下边播。
     * PR5 后作回退；优先 {@link #resolveOutputMediaUrl}。
     *
     * @param rangeHeader 请求头 Range，可为 null
     */
    public ResponseEntity<Resource> openOutputMedia(Long taskId, String rangeHeader) {
        AigenTaskEntity entity = requireOwnedTask(taskId);
        long len = storageService.headOutput(entity).map(m -> m.getSizeBytes()).orElse(0L);
        return com.dwcode.okxbot.common.web.MediaRangeSupport.build(
                rangeHeader,
                len,
                "video/mp4",
                "output.mp4",
                (start, end) -> storageService.openOutputMedia(entity, start, end));
    }

    public Map<String, Object> getStoryboard(Long taskId) {
        AigenTaskEntity entity = requireOwnedTask(taskId);
        if (entity.getStoryboardJson() == null || entity.getStoryboardJson().isBlank()) {
            throw new BusinessException(404, "分镜尚未生成");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(entity.getStoryboardJson(), Map.class);
            return map;
        } catch (Exception e) {
            throw new BusinessException("解析 storyboard 失败: " + e.getMessage());
        }
    }

    // ---------- VT-1.5 镜头级 API ----------

    public List<AigenShotSummary> listShots(Long taskId) {
        AigenTaskEntity entity = requireOwnedTask(taskId);
        // 进行中/排队时尚无 storyboardJson：返回空列表，避免页面进详情报「content is null」
        if (entity.getStoryboardJson() == null || entity.getStoryboardJson().isBlank()) {
            return List.of();
        }
        ShotlistDto list = loadShotlist(entity);
        if (list == null || list.getShots() == null) {
            return List.of();
        }
        Path workDir = resolveWorkDir(entity);
        List<AigenShotSummary> out = new ArrayList<>();
        for (ShotDto s : list.getShots()) {
            out.add(toShotSummary(entity, workDir, s));
        }
        return out;
    }

    public ResponseEntity<Resource> openShotImage(Long taskId, String shotId) {
        AigenTaskEntity entity = requireOwnedTask(taskId);
        ShotlistDto list = loadShotlist(entity);
        ShotDto shot = findShot(list, shotId);
        Path workDir = resolveWorkDir(entity);
        if (shot.getVisual() == null) {
            throw new BusinessException(404, "镜头尚无画面文件");
        }
        // 页面 <img> 必须用静图：优先 assetPath 图片；若误指 mp4 则找同名 jpg
        Path path = resolveShotPreviewImage(workDir, shot);
        String fileName = "shot.jpg";
        InputStream in;
        if (path != null && Files.isRegularFile(path)) {
            fileName = path.getFileName().toString();
            try {
                in = Files.newInputStream(path);
            } catch (IOException e) {
                throw new BusinessException("打开镜头图失败: " + e.getMessage());
            }
        } else {
            // 对象存储：按相对路径打开
            String rel = resolveShotPreviewRelative(shot);
            if (rel == null) {
                throw new BusinessException(404, "镜头图片不存在（仅有视频或文件缺失）");
            }
            fileName = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
            in = storageService.openRelative(entity, rel);
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        MediaType mt = name.endsWith(".png") ? MediaType.IMAGE_PNG
                : name.endsWith(".webp") ? MediaType.parseMediaType("image/webp")
                : MediaType.IMAGE_JPEG;
        final String outName = fileName;
        Resource resource = new InputStreamResource(in) {
            @Override
            public String getFilename() {
                return outName;
            }
        };
        return ResponseEntity.ok()
                .contentType(mt)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + outName + "\"")
                .body(resource);
    }

    /** 无本地文件时，从 shot 解析对象存储相对路径 */
    private String resolveShotPreviewRelative(ShotDto shot) {
        if (shot == null || shot.getVisual() == null) {
            return null;
        }
        String asset = shot.getVisual().getAssetPath();
        if (asset == null || asset.isBlank()) {
            return null;
        }
        String rel = asset.replace('\\', '/');
        if (looksLikeImageName(rel)) {
            return rel;
        }
        if (looksLikeVideoName(rel)) {
            int dot = rel.lastIndexOf('.');
            String base = dot > 0 ? rel.substring(0, dot) : rel;
            return base + ".jpg";
        }
        return null;
    }

    /**
     * 解析页面缩略图用的静图路径（不返回 mp4）。
     */
    private Path resolveShotPreviewImage(Path workDir, ShotDto shot) {
        if (shot == null || shot.getVisual() == null || workDir == null) {
            return null;
        }
        Path root = workDir.toAbsolutePath().normalize();
        // 1) assetPath 若是图片
        String asset = shot.getVisual().getAssetPath();
        if (asset != null && !asset.isBlank()) {
            Path p = workDir.resolve(asset).normalize();
            if (p.startsWith(root) && Files.isRegularFile(p) && looksLikeImageName(p.getFileName().toString())) {
                return p;
            }
            // assetPath 误为视频：旁路找同名静图
            if (p.startsWith(root) && looksLikeVideoName(p.getFileName().toString())) {
                Path still = siblingStillImage(p);
                if (still != null) {
                    return still;
                }
            }
        }
        // 2) videoPath 旁的静图
        String video = shot.getVisual().getVideoPath();
        if (video != null && !video.isBlank()) {
            Path vp = workDir.resolve(video).normalize();
            if (vp.startsWith(root)) {
                Path still = siblingStillImage(vp);
                if (still != null) {
                    return still;
                }
            }
        }
        return null;
    }

    private static boolean looksLikeVideoName(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".webm") || n.endsWith(".mov");
    }

    private static Path siblingStillImage(Path videoOrAny) {
        if (videoOrAny == null || videoOrAny.getParent() == null) {
            return null;
        }
        String file = videoOrAny.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String stem = dot > 0 ? file.substring(0, dot) : file;
        if (stem.endsWith("-svd")) {
            stem = stem.substring(0, stem.length() - 4);
        }
        for (String ext : new String[]{".jpg", ".jpeg", ".png", ".webp"}) {
            Path cand = videoOrAny.getParent().resolve(stem + ext);
            if (Files.isRegularFile(cand)) {
                return cand;
            }
        }
        return null;
    }

    /**
     * 单镜重生图（可选润色）；默认完成后重渲染整片。
     */
    public AigenTaskResponse regenerateShot(Long taskId, String shotId, Boolean enhance, Boolean reRender) {
        AigenTaskEntity entity = requireOwnedTask(taskId);
        requireVisualTerminal(entity);
        ShotlistDto list = loadShotlist(entity);
        ShotDto shot = findShot(list, shotId);
        Path workDir = resolveWorkDir(entity);
        try {
            AspectRatioMapper.Size size = visualShotAssetService.resolveFluxSize(entity, list);
            boolean mock = aigenProperties.isMockPipeline()
                    || "mock".equalsIgnoreCase(aigenProperties.getSteps().getAsset());
            boolean doEnhance = enhance != null ? enhance
                    : (entity.getEnhanceImagePrompt() != null && entity.getEnhanceImagePrompt() == 1);
            // 强制按 ai_image 重生（覆盖 user_image）
            if (shot.getVisual() != null) {
                shot.getVisual().setType("ai_image");
            }
            visualShotAssetService.materializeShotImage(
                    entity, workDir, shot, size, 1, mock, doEnhance);
            persistShotlistEntity(entity, list, workDir);

            boolean doRender = reRender == null || reRender;
            if (doRender && !aigenProperties.isMockPipeline()
                    && !"mock".equalsIgnoreCase(aigenProperties.getSteps().getRender())) {
                renderVisualNow(entity, list, workDir);
            }
            entity.setUpdatedAt(LocalDateTime.now());
            aigenTaskMapper.updateById(entity);
            eventPublisher.publishEntity(entity, AigenTaskEventPublisher.TYPE_STATUS);
            return toResponse(entity);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("单镜重生失败: " + e.getMessage());
        }
    }

    /**
     * 上传用户图替换某镜主视觉。
     */
    public AigenShotSummary uploadShotImage(Long taskId, String shotId, MultipartFile file, Boolean reRender) {
        AigenTaskEntity entity = requireOwnedTask(taskId);
        requireVisualTerminal(entity);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请上传图片文件");
        }
        String ct = file.getContentType() != null ? file.getContentType() : "";
        if (!ct.startsWith("image/") && !looksLikeImageName(file.getOriginalFilename())) {
            throw new BusinessException(400, "仅支持图片文件");
        }
        ShotlistDto list = loadShotlist(entity);
        ShotDto shot = findShot(list, shotId);
        Path workDir = resolveWorkDir(entity);
        try {
            visualShotAssetService.saveUserImage(workDir, shot, file.getBytes(), file.getOriginalFilename());
            // 与 AI 出图一致：上传静图后也走动效片段，避免仅 CSS 运镜
            try {
                AspectRatioMapper.Size size = visualShotAssetService.resolveFluxSize(entity, list);
                Path still = workDir.resolve(shot.getVisual().getAssetPath()).normalize();
                if (Files.isRegularFile(still)) {
                    visualShotAssetService.upgradeStillToMotion(
                            entity, workDir, shot, still, size.width(), size.height(), 1);
                }
            } catch (Exception i2vEx) {
                log.warn("上传图动效升级跳过: {}", i2vEx.getMessage());
            }
            persistShotlistEntity(entity, list, workDir);
            boolean doRender = reRender == null || reRender;
            if (doRender && !aigenProperties.isMockPipeline()
                    && !"mock".equalsIgnoreCase(aigenProperties.getSteps().getRender())) {
                renderVisualNow(entity, list, workDir);
            }
            entity.setUpdatedAt(LocalDateTime.now());
            aigenTaskMapper.updateById(entity);
            eventPublisher.publishEntity(entity, AigenTaskEventPublisher.TYPE_STATUS);
            return toShotSummary(entity, workDir, shot);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("上传镜头图失败: " + e.getMessage());
        }
    }

    private void requireVisualTerminal(AigenTaskEntity entity) {
        if (!"visual".equalsIgnoreCase(entity.getPipelineMode())) {
            throw new BusinessException(400, "仅画面短片模式支持镜头操作");
        }
        String st = entity.getStatus();
        if (!AigenTaskStatus.SUCCESS.name().equals(st)
                && !AigenTaskStatus.FAILED.name().equals(st)
                && !AigenTaskStatus.PAUSED.name().equals(st)) {
            throw new BusinessException(400, "仅成功/失败/暂停的任务可编辑镜头，当前: " + st);
        }
        if (entity.getStoryboardJson() == null || entity.getStoryboardJson().isBlank()) {
            throw new BusinessException(400, "镜头表尚未生成");
        }
    }

    private ShotlistDto loadShotlist(AigenTaskEntity entity) {
        String json = entity != null ? entity.getStoryboardJson() : null;
        if (json == null || json.isBlank()) {
            // 规划完成前正常为空；调用方应先判空，此处兜底避免 Jackson NPE
            throw new BusinessException(404, "镜头表尚未生成");
        }
        try {
            return objectMapper.readValue(json, ShotlistDto.class);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "解析镜头表失败: " + e.getMessage());
        }
    }

    private ShotDto findShot(ShotlistDto list, String shotId) {
        if (list.getShots() == null) {
            throw new BusinessException(404, "镜头不存在: " + shotId);
        }
        return list.getShots().stream()
                .filter(s -> shotId != null && shotId.equals(s.getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "镜头不存在: " + shotId));
    }

    private Path resolveWorkDir(AigenTaskEntity entity) {
        if (entity.getWorkDir() != null && !entity.getWorkDir().isBlank()) {
            Path p = Path.of(entity.getWorkDir()).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return storageService.resolveTaskDir(String.valueOf(entity.getId()));
    }

    private void persistShotlistEntity(AigenTaskEntity entity, ShotlistDto list, Path workDir) throws Exception {
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(list);
        Path sbPath = workDir.resolve("shotlist.json");
        Files.writeString(sbPath, json);
        Files.writeString(workDir.resolve("storyboard.json"), json);
        entity.setStoryboardJson(json);
        entity.setStoryboardPath(sbPath.toAbsolutePath().toString());
        entity.setShotCount(list.getShots() != null ? list.getShots().size() : 0);
        if (list.getMeta() != null && list.getMeta().getDurationInFrames() != null
                && list.getMeta().getFps() != null && list.getMeta().getFps() > 0) {
            entity.setDurationSeconds(
                    list.getMeta().getDurationInFrames() / (double) list.getMeta().getFps());
        }
    }

    private void renderVisualNow(AigenTaskEntity entity, ShotlistDto list, Path workDir) throws Exception {
        String compositionId = aigenProperties.getVisual().getCompositionId();
        if (compositionId == null || compositionId.isBlank()) {
            compositionId = "VisualTimeline";
        }
        entity.setStatus(AigenTaskStatus.RENDERING.name());
        entity.setCurrentStep("正在重新合成视频…");
        entity.setProgress(85);
        aigenTaskMapper.updateById(entity);
        eventPublisher.publishEntity(entity, AigenTaskEventPublisher.TYPE_STATUS);

        long t0 = System.currentTimeMillis();
        RenderResult result = videoRenderPort.render(RenderCommand.builder()
                .jobId(String.valueOf(entity.getId()))
                .compositionId(compositionId)
                .inputProps(list)
                .workDir(workDir)
                .outputFileName("output.mp4")
                .build());
        if (!result.isSuccess()) {
            entity.setStatus(AigenTaskStatus.FAILED.name());
            entity.setCurrentStep("重渲失败");
            entity.setErrorMessage(result.getError() != null ? result.getError() : "渲染失败");
            aigenTaskMapper.updateById(entity);
            throw new BusinessException(entity.getErrorMessage());
        }
        Path out = null;
        if (result.getOutputAbsolutePath() != null) {
            out = Path.of(result.getOutputAbsolutePath());
        }
        if (out == null || !Files.isRegularFile(out) || Files.size(out) < 1024L) {
            Path fallback = workDir.resolve("output.mp4");
            if (Files.isRegularFile(fallback) && Files.size(fallback) >= 1024L) {
                out = fallback;
            }
        }
        if (out == null || !Files.isRegularFile(out) || Files.size(out) < 1024L) {
            entity.setStatus(AigenTaskStatus.FAILED.name());
            entity.setCurrentStep("重渲失败");
            entity.setErrorMessage("重渲未产出有效 MP4");
            aigenTaskMapper.updateById(entity);
            throw new BusinessException(entity.getErrorMessage());
        }
        entity.setOutputPath(out.toAbsolutePath().toString());
        entity.setOutputSizeBytes(Files.size(out));
        entity.setRenderDurationMs(System.currentTimeMillis() - t0);
        entity.setStatus(AigenTaskStatus.SUCCESS.name());
        entity.setCurrentStep("生成完成");
        entity.setProgress(100);
        entity.setErrorMessage("");
        entity.setFinishedAt(LocalDateTime.now());
    }

    private AigenShotSummary toShotSummary(AigenTaskEntity entity, Path workDir, ShotDto s) {
        // 页面能否出缩略图：本地静图 或 对象存储（成功后 scratch 已清，图只在 R2）
        Path previewFile = resolveShotPreviewImage(workDir, s);
        String stillRel = null;
        if (previewFile != null && workDir != null) {
            try {
                stillRel = workDir.toAbsolutePath().normalize()
                        .relativize(previewFile.toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (stillRel == null || stillRel.isBlank()) {
            stillRel = resolveShotPreviewRelative(s);
        }
        boolean img = previewFile != null
                || (stillRel != null && storageService.relativeExists(entity, stillRel));
        boolean audio = false;
        if (s.getAudioSrc() != null && !s.getAudioSrc().isBlank()) {
            String aRel = s.getAudioSrc().replace('\\', '/');
            Path p = workDir != null ? workDir.resolve(aRel).normalize() : null;
            audio = (p != null && Files.isRegularFile(p))
                    || storageService.relativeExists(entity, aRel);
        }
        String prompt = s.getVisual() != null ? s.getVisual().getPrompt() : null;
        String preview = prompt;
        if (preview != null && preview.length() > 80) {
            preview = preview.substring(0, 80) + "…";
        }
        // 对外 assetPath 尽量返回静图相对路径
        String assetPath = stillRel != null ? stillRel
                : (s.getVisual() != null ? s.getVisual().getAssetPath() : null);
        return AigenShotSummary.builder()
                .id(s.getId())
                .order(s.getOrder())
                .durationSec(s.getDurationSec())
                .title(s.getOverlay() != null ? s.getOverlay().getTitle() : null)
                .visualType(s.getVisual() != null ? s.getVisual().getType() : null)
                .assetPath(assetPath)
                .imageAvailable(img)
                .audioSrc(s.getAudioSrc())
                .audioAvailable(audio)
                .motionType(s.getMotion() != null ? s.getMotion().getType() : null)
                .layout(s.getOverlay() != null ? s.getOverlay().getLayout() : null)
                .promptPreview(preview)
                .build();
    }

    private static boolean looksLikeImageName(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
    }

    private int countUserInFlight(Long userId) {
        if (userId == null) {
            return 0;
        }
        Long cnt = aigenTaskMapper.selectCount(
                new LambdaQueryWrapper<AigenTaskEntity>()
                        .eq(AigenTaskEntity::getUserId, userId)
                        .in(AigenTaskEntity::getStatus,
                                AigenTaskStatus.PENDING.name(),
                                AigenTaskStatus.PLANNING.name(),
                                AigenTaskStatus.ASSET_GENERATING.name(),
                                AigenTaskStatus.RENDERING.name())
        );
        return cnt == null ? 0 : cnt.intValue();
    }

    private AigenTaskEntity requireOwnedTask(Long taskId) {
        AigenTaskEntity entity = aigenTaskMapper.selectById(taskId);
        if (entity == null) {
            throw new BusinessException(404, "任务不存在: " + taskId);
        }
        Long userId = SecurityUtils.requireCurrentUserId();
        if (entity.getUserId() == null || !entity.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问该任务");
        }
        return entity;
    }

    private AigenTaskResponse toResponse(AigenTaskEntity e) {
        boolean outputAvailable = storageService.mediaAvailable(e.getOutputPath());
        return AigenTaskResponse.builder()
                .id(String.valueOf(e.getId()))
                .title(e.getTitle())
                .prompt(e.getPrompt())
                .templateId(e.getTemplateId())
                .pipelineMode(e.getPipelineMode() != null ? e.getPipelineMode() : "template")
                .audioMode(e.getAudioMode())
                .stylePreset(e.getStylePreset())
                .shotCount(e.getShotCount())
                .assetDoneCount(e.getAssetDoneCount())
                .status(e.getStatus())
                .currentStep(e.getCurrentStep())
                .progress(e.getProgress() != null ? e.getProgress() : 0)
                .language(e.getLanguage())
                .aspectRatio(e.getAspectRatio())
                .targetDurationSec(e.getTargetDurationSec())
                .voiceId(e.getVoiceId())
                .bgmId(e.getBgmId())
                .llmProvider(e.getLlmProvider())
                .llmModel(e.getLlmModel())
                .imageProvider(e.getImageProvider())
                .imageModel(e.getImageModel())
                .enhanceImagePrompt(e.getEnhanceImagePrompt() != null && e.getEnhanceImagePrompt() == 1)
                // 空串归一，避免 null 被前端当成「字段缺失」而残留旧值
                .errorMessage(e.getErrorMessage() == null ? "" : e.getErrorMessage())
                .durationSeconds(e.getDurationSeconds())
                .outputAvailable(outputAvailable)
                .planDurationMs(e.getPlanDurationMs())
                .assetDurationMs(e.getAssetDurationMs())
                .renderDurationMs(e.getRenderDurationMs())
                .totalDurationMs(e.getTotalDurationMs())
                .startedAt(formatTime(e.getStartedAt()))
                .finishedAt(formatTime(e.getFinishedAt()))
                .createdAt(formatTime(e.getCreatedAt()))
                .updatedAt(formatTime(e.getUpdatedAt()))
                .build();
    }

    private static String deriveTitle(String prompt) {
        String t = prompt.trim().replaceAll("\\s+", " ");
        return t.length() <= 40 ? t : t.substring(0, 40) + "…";
    }

    /** 根据提示词字符粗略判定语言：含较多 CJK 则为 zh，否则 en */
    private static String detectLanguageFromPrompt(String text) {
        if (text == null || text.isBlank()) {
            return "zh";
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(text.charAt(i));
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                cjk++;
            }
        }
        return cjk * 2 >= Math.max(1, text.length() / 4) ? "zh" : "en";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private boolean hasConfiguredBgmFile() {
        try {
            String dir = aigenProperties.getVisual().getBgmDir();
            if (dir == null || dir.isBlank()) {
                return false;
            }
            Path bgmDir = Path.of(dir).toAbsolutePath().normalize();
            if (!Files.isDirectory(bgmDir)) {
                return false;
            }
            try (var stream = Files.list(bgmDir)) {
                return stream.anyMatch(p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return Files.isRegularFile(p)
                            && (n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".m4a"));
                });
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static String formatTime(LocalDateTime t) {
        return t == null ? null : DT_FMT.format(t);
    }
}
