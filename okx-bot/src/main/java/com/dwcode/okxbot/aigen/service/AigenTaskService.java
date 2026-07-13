package com.dwcode.okxbot.aigen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dwcode.okxbot.aigen.agent.AigenTaskScheduler;
import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.dto.*;
import com.dwcode.okxbot.aigen.entity.AigenTaskEntity;
import com.dwcode.okxbot.aigen.enums.AigenTaskStatus;
import com.dwcode.okxbot.aigen.event.AigenTaskEventPublisher;
import com.dwcode.okxbot.aigen.mapper.AigenTaskMapper;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.chat.config.AiProperties.ProviderConfig;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.service.AiModelConfigService;
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
import java.util.List;
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

    public AigenTaskResponse create(AigenCreateRequest request) {
        String prompt = request.getPrompt().trim();
        if (prompt.isEmpty()) {
            throw new BusinessException(400, "prompt 不能为空");
        }

        AigenCreateOptions options = request.getOptions() != null
                ? request.getOptions()
                : new AigenCreateOptions();

        String templateId = blankToNull(request.getTemplateId());
        if (templateId == null) {
            templateId = TemplateRegistry.KNOWLEDGE_CARDS;
        }
        if (!templateRegistry.exists(templateId)) {
            throw new BusinessException(400, "不支持的模板: " + templateId);
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
        if (language == null) {
            language = "zh";
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

        AigenTaskEntity entity = new AigenTaskEntity();
        entity.setUserId(SecurityUtils.requireCurrentUserId());
        entity.setPrompt(prompt);
        entity.setTitle(deriveTitle(prompt));
        entity.setNegativePrompt(blankToNull(options.getNegativePrompt()));
        entity.setTemplateId(templateId);
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
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        aigenTaskMapper.insert(entity);

        log.info("创建 aigen 任务: id={}, template={}, duration={}s, mockPipeline={}, llm={}/{}",
                entity.getId(), templateId, duration, aigenProperties.isMockPipeline(),
                llmProvider, llmModel);
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
        }

        // 清空工作目录中的旧分镜/成片，避免成功任务重跑残留文件
        try {
            storageService.deleteTaskDir(String.valueOf(taskId));
        } catch (Exception e) {
            log.warn("重试前清理任务目录失败 taskId={}: {}", taskId, e.getMessage());
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
            storageService.deleteTaskDir(String.valueOf(taskId));
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
     * 鉴权后流式输出成片。
     */
    public ResponseEntity<Resource> openOutputMedia(Long taskId) {
        AigenTaskEntity entity = requireOwnedTask(taskId);
        if (entity.getOutputPath() == null || entity.getOutputPath().isBlank()) {
            throw new BusinessException(404, "成片尚未生成");
        }
        Path path = Path.of(entity.getOutputPath()).toAbsolutePath().normalize();
        Path workRoot = storageService.resolveWorkRoot();
        if (!path.startsWith(workRoot)) {
            throw new BusinessException(403, "非法成片路径");
        }
        if (!Files.isRegularFile(path)) {
            throw new BusinessException(404, "成片文件不存在");
        }
        Resource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"output.mp4\"")
                .body(resource);
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

    private AigenTaskEntity requireOwnedTask(Long taskId) {
        AigenTaskEntity entity = aigenTaskMapper.selectById(taskId);
        if (entity == null) {
            throw new BusinessException(404, "任务不存在: " + taskId);
        }
        Long userId = SecurityUtils.requireCurrentUserId();
        if (entity.getUserId() != null && !entity.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问该任务");
        }
        return entity;
    }

    private AigenTaskResponse toResponse(AigenTaskEntity e) {
        boolean outputAvailable = e.getOutputPath() != null
                && !e.getOutputPath().isBlank()
                && Files.isRegularFile(Path.of(e.getOutputPath()));
        return AigenTaskResponse.builder()
                .id(String.valueOf(e.getId()))
                .title(e.getTitle())
                .prompt(e.getPrompt())
                .templateId(e.getTemplateId())
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

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String formatTime(LocalDateTime t) {
        return t == null ? null : DT_FMT.format(t);
    }
}
