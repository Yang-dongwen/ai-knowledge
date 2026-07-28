package com.dwcode.okxbot.article.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dwcode.okxbot.article.agent.ArticleTaskScheduler;
import com.dwcode.okxbot.article.config.ArticleProperties;
import com.dwcode.okxbot.article.dto.*;
import com.dwcode.okxbot.article.entity.ArticleTaskEntity;
import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import com.dwcode.okxbot.article.enums.ArticleSupportLevel;
import com.dwcode.okxbot.article.enums.ArticleTaskStatus;
import com.dwcode.okxbot.article.event.ArticleTaskEventPublisher;
import com.dwcode.okxbot.article.mapper.ArticleTaskMapper;
import com.dwcode.okxbot.article.security.ArticleSafetyException;
import com.dwcode.okxbot.article.security.UrlSafetyGuard;
import com.dwcode.okxbot.article.util.ArticlePlatformDetector;
import com.dwcode.okxbot.article.util.ArticlePlatformInfo;
import com.dwcode.okxbot.article.util.ArticleUrlNormalizer;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.common.ai.AiModelConfigService;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleTaskService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_PASTE_CHARS = 100_000;
    private static final String SUBMIT_DISCLAIMER =
            "请仅提交您有权访问的内容。系统可能抓取公开网页；二次创作结果仅供学习研究，请遵守版权与平台规范，勿用于未授权商用传播。";
    private static final String RESULT_DISCLAIMER =
            "内容来源于公开网页或用户粘贴，AI 提取与改写可能有误，请自行核验事实与版权。";

    private static final Set<String> RETRYABLE = Set.of(
            ArticleTaskStatus.FAILED.name(),
            ArticleTaskStatus.CANCELLED.name(),
            ArticleTaskStatus.PAUSED.name(),
            ArticleTaskStatus.SUCCESS.name()
    );

    private final ArticleTaskMapper taskMapper;
    private final ArticleTaskScheduler taskScheduler;
    private final ArticleTaskEventPublisher eventPublisher;
    private final ArticleStorageService storageService;
    private final ArticleProperties properties;
    private final ArticlePlatformDetector platformDetector;
    private final UrlSafetyGuard urlSafetyGuard;
    private final AiModelConfigService aiModelConfigService;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    public ArticleTaskResponse create(ArticleCreateRequest request) {
        if (!properties.isEnabled()) {
            throw new BusinessException(400, "文章提取功能未启用");
        }
        Long userId = SecurityUtils.requireCurrentUserId();
        int inFlight = taskScheduler.countUserInFlight(userId);
        if (inFlight >= properties.getMaxConcurrentTasksPerUser()) {
            throw new BusinessException(429, "并发任务数已达上限（"
                    + properties.getMaxConcurrentTasksPerUser() + "），请等待完成后再提交");
        }

        String url = blankToNull(request.getUrl());
        String paste = blankToNull(request.getPasteText());
        if (url == null && paste == null) {
            throw new BusinessException(400, "url 与 pasteText 至少提供一个");
        }
        if (paste != null && paste.length() > MAX_PASTE_CHARS) {
            throw new BusinessException(400, "pasteText 超过上限 " + MAX_PASTE_CHARS);
        }

        String normalizedUrl = null;
        if (url != null) {
            normalizedUrl = ArticleUrlNormalizer.normalize(url);
            try {
                urlSafetyGuard.assertSafeUrl(normalizedUrl);
            } catch (ArticleSafetyException e) {
                throw new BusinessException(400, e.getErrorCode() + ": " + e.getMessage());
            }
        }

        ArticleCreateOptions options = request.getOptions() != null
                ? request.getOptions() : new ArticleCreateOptions();
        boolean forcePaste = Boolean.TRUE.equals(options.getForcePasteOnly());
        boolean allowFallback = !Boolean.FALSE.equals(options.getAllowPasteFallback());
        boolean genRewrite = !Boolean.FALSE.equals(options.getGenerateRewrite());
        boolean mindMap = Boolean.TRUE.equals(options.getExtractMindMap());

        ArticlePlatformInfo platformInfo = platformDetector.detect(normalizedUrl != null ? normalizedUrl : "");
        if (normalizedUrl == null && paste != null) {
            platformInfo = ArticlePlatformInfo.builder()
                    .platform("paste")
                    .supportLevel(ArticleSupportLevel.FULL)
                    .message("纯粘贴输入")
                    .build();
        }

        String inputMode;
        if (normalizedUrl != null && paste != null) {
            inputMode = "url_and_paste";
        } else if (paste != null) {
            inputMode = "paste";
        } else {
            inputMode = "url";
        }

        LlmPair llm = resolveChatLlm(options.getLlmProvider(), options.getLlmModel());
        List<String> variants = options.getRewriteVariants();
        if (variants == null || variants.isEmpty()) {
            variants = properties.getRewrite().getDefaultVariants();
        }
        variants = sanitizeVariants(variants);

        ArticleTaskEntity entity = new ArticleTaskEntity();
        entity.setUserId(userId);
        entity.setSourceUrl(normalizedUrl);
        entity.setCanonicalUrl(normalizedUrl);
        entity.setPlatform(platformInfo.getPlatform());
        entity.setSupportLevel(platformInfo.getSupportLevel() != null
                ? platformInfo.getSupportLevel().name() : ArticleSupportLevel.FULL.name());
        entity.setTitle(null);
        entity.setLanguage(blankToNull(options.getLanguage()) != null ? options.getLanguage() : "zh");
        entity.setInputMode(inputMode);
        entity.setPasteText(paste);
        entity.setForcePasteOnly(forcePaste ? 1 : 0);
        entity.setAllowPasteFallback(allowFallback ? 1 : 0);
        entity.setPasteResume(0);
        entity.setLlmProvider(llm.provider());
        entity.setLlmModel(llm.model());
        entity.setExtractMindMap(mindMap ? 1 : 0);
        entity.setGenerateRewrite(genRewrite ? 1 : 0);
        try {
            entity.setRewriteVariants(objectMapper.writeValueAsString(variants));
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("forcePasteOnly", forcePaste);
            snap.put("allowPasteFallback", allowFallback);
            snap.put("rewriteVariants", variants);
            entity.setRequestOptionsJson(objectMapper.writeValueAsString(snap));
        } catch (Exception e) {
            entity.setRewriteVariants("[]");
        }
        entity.setProgress(0);
        entity.setDegraded(0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        // PASTE_ONLY / UNSUPPORTED 无 paste → 直接 NEEDS_PASTE（零 HTTP、不进调度）
        ArticleSupportLevel sl = platformInfo.getSupportLevel();
        if ((sl == ArticleSupportLevel.PASTE_ONLY || sl == ArticleSupportLevel.UNSUPPORTED)
                && paste == null) {
            entity.setStatus(ArticleTaskStatus.NEEDS_PASTE.name());
            entity.setCurrentStep("需要粘贴正文");
            entity.setProgress(35);
            entity.setErrorCode(sl == ArticleSupportLevel.PASTE_ONLY
                    ? ArticleErrorCode.PLATFORM_PASTE_ONLY
                    : ArticleErrorCode.PLATFORM_UNSUPPORTED);
            entity.setErrorMessage(platformInfo.getMessage() != null
                    ? platformInfo.getMessage()
                    : "请粘贴正文后继续");
            entity.setDegraded(1);
            entity.setDegradeReason(entity.getErrorCode() + ":" + entity.getErrorMessage());
            entity.setFinishedAt(LocalDateTime.now());
            taskMapper.insert(entity);
            eventPublisher.publishEntity(entity, ArticleTaskEventPublisher.TYPE_CREATED);
            eventPublisher.publishEntity(entity, ArticleTaskEventPublisher.TYPE_STATUS);
            return toResponse(entity, true);
        }

        entity.setStatus(ArticleTaskStatus.PENDING.name());
        entity.setCurrentStep("排队中");
        taskMapper.insert(entity);
        log.info("创建 article 任务: id={} platform={} mode={} llm={}/{}",
                entity.getId(), entity.getPlatform(), inputMode, llm.provider(), llm.model());
        eventPublisher.publishEntity(entity, ArticleTaskEventPublisher.TYPE_CREATED);
        taskScheduler.notifyPending();
        return toResponse(entity, false);
    }

    public ArticleTaskResponse getTask(Long taskId) {
        return toResponse(requireOwnedTask(taskId), true);
    }

    public ArticleTaskPageResponse listTasks(int page, int size, String status) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Long userId = SecurityUtils.requireCurrentUserId();

        LambdaQueryWrapper<ArticleTaskEntity> q = new LambdaQueryWrapper<ArticleTaskEntity>()
                .eq(ArticleTaskEntity::getUserId, userId)
                .orderByDesc(ArticleTaskEntity::getCreatedAt);
        if (status != null && !status.isBlank()) {
            ArticleTaskStatus st = ArticleTaskStatus.from(status);
            if (st == null) {
                throw new BusinessException(400, "无效 status: " + status);
            }
            q.eq(ArticleTaskEntity::getStatus, st.name());
        }

        Page<ArticleTaskEntity> mpPage = new Page<>(safePage + 1L, safeSize);
        Page<ArticleTaskEntity> result = taskMapper.selectPage(mpPage, q);
        List<ArticleTaskResponse> items = result.getRecords().stream()
                .map(e -> toResponse(e, false))
                .collect(Collectors.toList());

        return ArticleTaskPageResponse.builder()
                .items(items)
                .total(result.getTotal())
                .page(safePage)
                .size(safeSize)
                .build();
    }

    public Object getCore(Long taskId) {
        ArticleTaskEntity e = requireOwnedTask(taskId);
        return parseJson(e.getCoreJson());
    }

    public Object getRewrite(Long taskId) {
        ArticleTaskEntity e = requireOwnedTask(taskId);
        return parseJson(e.getRewriteJson());
    }

    public Map<String, Object> getMainText(Long taskId) {
        ArticleTaskEntity e = requireOwnedTask(taskId);
        String text = storageService.readMainTextFromPath(e);
        if (text == null) {
            text = e.getMainText();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(e.getId()));
        m.put("mainText", text);
        m.put("mainTextChars", e.getMainTextChars());
        return m;
    }

    public ArticleTaskResponse paste(Long taskId, ArticlePasteRequest request) {
        ArticleTaskEntity entity = requireOwnedTask(taskId);
        String status = entity.getStatus();
        if (!ArticleTaskStatus.NEEDS_PASTE.name().equals(status)) {
            throw new BusinessException(400, "仅 NEEDS_PASTE 状态可粘贴续跑，当前: " + status);
        }
        String paste = request.getPasteText() != null ? request.getPasteText().trim() : "";
        if (paste.isEmpty()) {
            throw new BusinessException(400, "pasteText 不能为空");
        }
        if (paste.length() > MAX_PASTE_CHARS) {
            throw new BusinessException(400, "pasteText 超过上限 " + MAX_PASTE_CHARS);
        }

        entity.setPasteText(paste);
        entity.setPasteResume(1);
        if (entity.getSourceUrl() != null && !entity.getSourceUrl().isBlank()) {
            entity.setInputMode("url_and_paste");
        } else {
            entity.setInputMode("paste");
        }
        entity.setStatus(ArticleTaskStatus.PENDING.name());
        entity.setCurrentStep("粘贴后续跑排队中");
        entity.setProgress(0);
        entity.setErrorCode(null);
        entity.setErrorMessage("");
        entity.setStartedAt(null);
        entity.setFinishedAt(null);
        entity.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(entity);
        eventPublisher.publishEntity(entity, ArticleTaskEventPublisher.TYPE_STATUS);
        taskScheduler.notifyPending();
        return toResponse(entity, false);
    }

    public ArticleTaskResponse cancelTask(Long taskId) {
        ArticleTaskEntity entity = requireOwnedTask(taskId);
        String status = entity.getStatus();
        ArticleTaskStatus st = ArticleTaskStatus.from(status);
        if (st == ArticleTaskStatus.SUCCESS || st == ArticleTaskStatus.FAILED
                || st == ArticleTaskStatus.CANCELLED) {
            throw new BusinessException(400, "当前状态不可取消: " + status);
        }
        if (ArticleTaskStatus.PENDING.name().equals(status)
                || ArticleTaskStatus.NEEDS_PASTE.name().equals(status)
                || ArticleTaskStatus.PAUSED.name().equals(status)) {
            entity.setStatus(ArticleTaskStatus.CANCELLED.name());
            entity.setCurrentStep(ArticleTaskStatus.PENDING.name().equals(status)
                    ? "已取消（未开始）" : "已取消");
            entity.setPasteResume(0);
            entity.setErrorMessage("");
            entity.setFinishedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(entity);
            taskScheduler.markFinished(taskId);
            eventPublisher.publishEntity(entity, ArticleTaskEventPublisher.TYPE_STATUS);
            return toResponse(entity, false);
        }
        // running：协作取消
        taskScheduler.requestCancel(taskId);
        entity.setCurrentStep("取消中…");
        entity.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(entity);
        eventPublisher.publishEntity(entity, ArticleTaskEventPublisher.TYPE_STATUS);
        return toResponse(entity, false);
    }

    public ArticleTaskResponse pauseTask(Long taskId) {
        ArticleTaskEntity entity = requireOwnedTask(taskId);
        String status = entity.getStatus();
        ArticleTaskStatus st = ArticleTaskStatus.from(status);
        if (st == null) {
            throw new BusinessException(400, "无效状态");
        }
        if (ArticleTaskStatus.PENDING.name().equals(status)) {
            entity.setStatus(ArticleTaskStatus.PAUSED.name());
            entity.setCurrentStep("已暂停（未开始）");
            entity.setPasteResume(0);
            entity.setErrorMessage("");
            entity.setFinishedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(entity);
            taskScheduler.markFinished(taskId);
            eventPublisher.publishEntity(entity, ArticleTaskEventPublisher.TYPE_STATUS);
            return toResponse(entity, false);
        }
        if (!st.isRunning()) {
            throw new BusinessException(400, "仅排队中或进行中的任务可暂停，当前: " + status);
        }
        taskScheduler.requestPause(taskId);
        entity.setCurrentStep("暂停中，等待当前步骤结束…");
        entity.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(entity);
        eventPublisher.publishEntity(entity, ArticleTaskEventPublisher.TYPE_STATUS);
        return toResponse(entity, false);
    }

    public ArticleTaskResponse retryTask(Long taskId, ArticleRetryRequest request) {
        ArticleTaskEntity entity = requireOwnedTask(taskId);
        String status = entity.getStatus() == null ? "" : entity.getStatus().trim().toUpperCase();
        if (ArticleTaskStatus.NEEDS_PASTE.name().equals(status)) {
            throw new BusinessException(400, "NEEDS_PASTE 请使用粘贴接口续跑，不可 retry");
        }
        if (!RETRYABLE.contains(status)) {
            throw new BusinessException(400, "当前状态不可重试: " + entity.getStatus());
        }

        if (request != null) {
            String p = blankToNull(request.getLlmProvider());
            String m = blankToNull(request.getLlmModel());
            if (p != null || m != null) {
                LlmPair llm = resolveChatLlm(
                        p != null ? p : entity.getLlmProvider(),
                        m != null ? m : entity.getLlmModel());
                entity.setLlmProvider(llm.provider());
                entity.setLlmModel(llm.model());
            }
            if (Boolean.TRUE.equals(request.getClearPaste())) {
                entity.setPasteText(null);
            }
        }

        try {
            storageService.deleteTaskStorage(entity.getUserId(), String.valueOf(taskId));
        } catch (Exception e) {
            log.warn("重试前清理存储失败: {}", e.getMessage());
        }

        taskScheduler.clearCancelRequest(taskId);
        taskScheduler.clearPauseRequest(taskId);
        entity.setPasteResume(0);
        entity.setStatus(ArticleTaskStatus.PENDING.name());
        entity.setCurrentStep("重试排队中");
        entity.setProgress(0);
        entity.setErrorCode(null);
        entity.setErrorMessage("");
        entity.setCoreJson(null);
        entity.setRewriteJson(null);
        entity.setResultJson(null);
        entity.setMainText(null);
        entity.setMainTextChars(null);
        entity.setMainTextPath(null);
        entity.setRawHtmlPath(null);
        entity.setResolveDurationMs(null);
        entity.setFetchDurationMs(null);
        entity.setExtractDurationMs(null);
        entity.setCoreDurationMs(null);
        entity.setRewriteDurationMs(null);
        entity.setTotalDurationMs(null);
        entity.setDegraded(0);
        entity.setDegradeReason(null);
        entity.setQualityScore(null);
        entity.setStartedAt(null);
        entity.setFinishedAt(null);
        entity.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(entity);
        eventPublisher.publishEntity(entity, ArticleTaskEventPublisher.TYPE_STATUS);
        taskScheduler.notifyPending();
        return toResponse(entity, false);
    }

    public void deleteTask(Long taskId) {
        ArticleTaskEntity entity = requireOwnedTask(taskId);
        Long ownerId = entity.getUserId();
        ArticleTaskStatus st = ArticleTaskStatus.from(entity.getStatus());
        if (st != null && (st.isRunning() || st == ArticleTaskStatus.PENDING)) {
            taskScheduler.requestCancel(taskId);
            taskScheduler.requestPause(taskId);
        }
        if (properties.isCleanupOnDelete()) {
            storageService.deleteTaskStorage(ownerId, String.valueOf(taskId));
        }
        int rows = taskMapper.deleteById(taskId);
        if (rows <= 0) {
            throw new BusinessException(404, "任务不存在或已删除");
        }
        taskScheduler.markFinished(taskId);
        eventPublisher.publishDeleted(ownerId, taskId);
    }

    public ArticlePlatformDetectResponse detectPlatform(ArticlePlatformDetectRequest request) {
        String url = request != null ? blankToNull(request.getUrl()) : null;
        ArticlePlatformInfo info = platformDetector.detect(url);
        return ArticlePlatformDetectResponse.builder()
                .url(url)
                .host(info.getHost())
                .platform(info.getPlatform())
                .supportLevel(info.getSupportLevel() != null ? info.getSupportLevel().name() : null)
                .message(info.getMessage())
                .skipFetch(platformDetector.shouldSkipFetch(info.getSupportLevel()))
                .build();
    }

    public List<Map<String, Object>> listChatModels() {
        return aiModelConfigService.listEnabledGroupedByProvider(AiModelConfigService.CAP_CHAT);
    }

    public String submitDisclaimer() {
        return SUBMIT_DISCLAIMER;
    }

    private ArticleTaskEntity requireOwnedTask(Long taskId) {
        if (taskId == null) {
            throw new BusinessException(400, "taskId 不能为空");
        }
        ArticleTaskEntity entity = taskMapper.selectById(taskId);
        if (entity == null) {
            throw new BusinessException(404, "任务不存在: " + taskId);
        }
        Long userId = SecurityUtils.requireCurrentUserId();
        if (!userId.equals(entity.getUserId())) {
            throw new BusinessException(403, "无权访问该任务");
        }
        return entity;
    }

    private ArticleTaskResponse toResponse(ArticleTaskEntity e, boolean detail) {
        ArticleTaskResponse.ArticleTaskResponseBuilder b = ArticleTaskResponse.builder()
                .id(String.valueOf(e.getId()))
                .userId(e.getUserId() != null ? String.valueOf(e.getUserId()) : null)
                .sourceUrl(e.getSourceUrl())
                .platform(e.getPlatform())
                .supportLevel(e.getSupportLevel())
                .title(e.getTitle())
                .status(e.getStatus())
                .currentStep(e.getCurrentStep())
                .progress(e.getProgress() != null ? e.getProgress() : 0)
                .inputMode(e.getInputMode())
                .llmProvider(e.getLlmProvider())
                .llmModel(e.getLlmModel())
                .mainTextChars(e.getMainTextChars())
                .degraded(e.getDegraded() != null && e.getDegraded() == 1)
                .errorCode(e.getErrorCode())
                .errorMessage(e.getErrorMessage())
                .totalDurationMs(e.getTotalDurationMs())
                .startedAt(formatTime(e.getStartedAt()))
                .finishedAt(formatTime(e.getFinishedAt()))
                .createdAt(formatTime(e.getCreatedAt()))
                .updatedAt(formatTime(e.getUpdatedAt()));

        if (detail) {
            b.canonicalUrl(e.getCanonicalUrl())
                    .author(e.getAuthor())
                    .language(e.getLanguage())
                    .extractMindMap(e.getExtractMindMap() != null && e.getExtractMindMap() == 1)
                    .generateRewrite(e.getGenerateRewrite() != null && e.getGenerateRewrite() == 1)
                    .rewriteVariants(parseVariants(e.getRewriteVariants()))
                    .degradeReason(e.getDegradeReason())
                    .qualityScore(e.getQualityScore())
                    .resolveDurationMs(e.getResolveDurationMs())
                    .fetchDurationMs(e.getFetchDurationMs())
                    .extractDurationMs(e.getExtractDurationMs())
                    .coreDurationMs(e.getCoreDurationMs())
                    .rewriteDurationMs(e.getRewriteDurationMs());

            if (ArticleTaskStatus.SUCCESS.name().equals(e.getStatus())) {
                b.core(parseJson(e.getCoreJson()))
                        .rewrite(parseJson(e.getRewriteJson()))
                        .disclaimer(RESULT_DISCLAIMER);
            } else if (ArticleTaskStatus.NEEDS_PASTE.name().equals(e.getStatus())
                    || ArticleTaskStatus.FAILED.name().equals(e.getStatus())) {
                b.disclaimer(SUBMIT_DISCLAIMER);
            }
        }
        return b.build();
    }

    private LlmPair resolveChatLlm(String provider, String model) {
        String p = blankToNull(provider);
        String m = blankToNull(model);
        if (p == null) {
            p = blankToNull(properties.getLlm().getDefaultProvider());
        }
        if (p == null) {
            // AiProperties.getDefaultProvider() 返回 ProviderConfig；优先取可用供应商 key
            var available = aiProperties.getAllAvailableProviders();
            if (available != null && !available.isEmpty()) {
                p = available.get(0).getKey();
            }
        }
        if (p == null) {
            // mock 允许无 key
            if (properties.isMockPipeline()) {
                return new LlmPair("mock", m != null ? m : "mock-chat");
            }
            var list = aiModelConfigService.listEnabledGroupedByProvider(AiModelConfigService.CAP_CHAT);
            if (!list.isEmpty()) {
                p = String.valueOf(list.get(0).get("key"));
            }
        }
        if (p == null) {
            throw new BusinessException(400, "未配置可用 LLM 供应商");
        }
        if (m == null) {
            m = blankToNull(properties.getLlm().getDefaultModel());
        }
        if (m == null) {
            m = aiModelConfigService.firstEnabledModelId(p, AiModelConfigService.CAP_CHAT);
        }
        if (m == null) {
            if (properties.isMockPipeline()) {
                m = "mock-chat";
            } else {
                throw new BusinessException(400, "供应商无可用 chat 模型: " + p);
            }
        }
        // 非 mock 时校验 api-key
        if (!properties.isMockPipeline()) {
            var pc = aiProperties.getProvider(p);
            if (pc == null || pc.getApiKey() == null || pc.getApiKey().isBlank()) {
                throw new BusinessException(400, "LLM 供应商未配置 api-key: " + p);
            }
        }
        return new LlmPair(p, m);
    }

    private List<String> sanitizeVariants(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String v : raw) {
                if (v != null && !v.isBlank()) {
                    out.add(v.trim());
                }
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        List<String> defaults = properties.getRewrite().getDefaultVariants();
        return defaults != null ? new ArrayList<>(defaults) : new ArrayList<>();
    }

    private List<String> parseVariants(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private Object parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (Exception e) {
            return raw;
        }
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static String formatTime(LocalDateTime t) {
        return t == null ? null : DT_FMT.format(t);
    }

    private record LlmPair(String provider, String model) {
    }
}
