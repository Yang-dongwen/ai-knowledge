package com.dwcode.okxbot.imggen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.chat.config.AiProperties.ProviderConfig;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.imggen.agent.ImgGenTaskScheduler;
import com.dwcode.okxbot.imggen.config.ImgGenProperties;
import com.dwcode.okxbot.imggen.dto.*;
import com.dwcode.okxbot.imggen.entity.ImgGenTaskEntity;
import com.dwcode.okxbot.imggen.enums.ImgGenTaskStatus;
import com.dwcode.okxbot.imggen.event.ImgGenTaskEventPublisher;
import com.dwcode.okxbot.imggen.mapper.ImgGenTaskMapper;
import com.dwcode.okxbot.imggen.port.PromptEnhancePort;
import com.dwcode.okxbot.imggen.util.AspectRatioMapper;
import com.dwcode.okxbot.video.service.AiModelConfigService;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImgGenTaskService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> RETRYABLE = Set.of(
            ImgGenTaskStatus.FAILED.name(),
            ImgGenTaskStatus.CANCELLED.name(),
            ImgGenTaskStatus.PAUSED.name(),
            ImgGenTaskStatus.SUCCESS.name()
    );

    private final ImgGenTaskMapper taskMapper;
    private final ImgGenTaskScheduler taskScheduler;
    private final ImgGenTaskEventPublisher eventPublisher;
    private final ImgGenStorageService storageService;
    private final ImgGenProperties properties;
    private final AiProperties aiProperties;
    private final AiModelConfigService aiModelConfigService;
    private final PromptEnhancePort promptEnhancePort;
    private final ObjectMapper objectMapper;

    /**
     * 独立润色：即时返回结果，不创建任务。由用户确认后再提交生图。
     */
    public ImgGenEnhanceResponse enhancePrompt(ImgGenEnhanceRequest request) {
        if (!properties.isEnabled()) {
            throw new BusinessException(400, "文生图功能未启用");
        }
        String original = request.getPrompt() != null ? request.getPrompt().trim() : "";
        if (original.isEmpty()) {
            throw new BusinessException(400, "prompt 不能为空");
        }
        if ("off".equalsIgnoreCase(properties.getSteps().getEnhance())) {
            throw new BusinessException(400, "润色步骤已关闭（imggen.steps.enhance=off）");
        }

        LlmPair llm = resolveEnhanceLlm(request.getLlmProvider(), request.getLlmModel());
        String languageHint = blankToNull(request.getLanguageHint());
        if (languageHint == null || "auto".equalsIgnoreCase(languageHint)) {
            languageHint = detectLanguageHint(original);
        }

        long t0 = System.currentTimeMillis();
        try {
            if ("mock".equalsIgnoreCase(properties.getSteps().getEnhance()) || properties.isMockPipeline()) {
                String mock = original + "，画面细腻，光影自然，构图均衡，高清质感";
                return ImgGenEnhanceResponse.builder()
                        .originalPrompt(original)
                        .enhancedPrompt(mock)
                        .llmProvider(llm.provider())
                        .llmModel(llm.model())
                        .latencyMs(System.currentTimeMillis() - t0)
                        .build();
            }
            String enhanced = promptEnhancePort.enhance(
                    original, languageHint, llm.provider(), llm.model());
            if (enhanced == null || enhanced.isBlank()) {
                throw new BusinessException(500, "润色结果为空");
            }
            long latency = System.currentTimeMillis() - t0;
            log.info("独立润色完成: userId={} inLen={} outLen={} latencyMs={} llm={}/{}",
                    SecurityUtils.requireCurrentUserId(), original.length(), enhanced.length(),
                    latency, llm.provider(), llm.model());
            return ImgGenEnhanceResponse.builder()
                    .originalPrompt(original)
                    .enhancedPrompt(enhanced.trim())
                    .llmProvider(llm.provider())
                    .llmModel(llm.model())
                    .latencyMs(latency)
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("独立润色失败: {}", e.getMessage());
            throw new BusinessException(500, "润色失败: " + e.getMessage());
        }
    }

    /** 粗略语言提示：含较多中日韩字符则 zh，否则 en */
    private static String detectLanguageHint(String text) {
        if (text == null || text.isEmpty()) {
            return "zh";
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN
                    || Character.UnicodeScript.of(c) == Character.UnicodeScript.HIRAGANA
                    || Character.UnicodeScript.of(c) == Character.UnicodeScript.KATAKANA
                    || Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL) {
                cjk++;
            }
        }
        return cjk * 2 >= text.length() / 4 ? "zh" : "en";
    }

    public ImgGenTaskResponse create(ImgGenCreateRequest request) {
        if (!properties.isEnabled()) {
            throw new BusinessException(400, "文生图功能未启用");
        }
        String prompt = request.getPrompt().trim();
        if (prompt.isEmpty()) {
            throw new BusinessException(400, "prompt 不能为空");
        }

        ImgGenCreateOptions options = request.getOptions() != null
                ? request.getOptions()
                : new ImgGenCreateOptions();

        String aspect = blankToNull(options.getAspectRatio());
        if (aspect == null) {
            aspect = "1:1";
        }
        AspectRatioMapper.Size size = AspectRatioMapper.map(aspect);

        int n = options.getN() != null ? options.getN() : 1;
        int maxN = Math.max(1, properties.getMaxN());
        if (n < 1 || n > maxN) {
            throw new BusinessException(400, "n 需在 1～" + maxN);
        }

        // —— 生图模型（数据库 ai_model_config capability=image）——
        boolean needReal = !properties.isMockPipeline()
                && !"mock".equalsIgnoreCase(properties.getSteps().getGenerate());
        String imageModelId = blankToNull(options.getImageModel());
        if (imageModelId == null && properties.getFlux() != null) {
            imageModelId = blankToNull(properties.getFlux().getDefaultModel());
        }
        String imageProviderHint = blankToNull(options.getImageProvider());
        if (imageProviderHint == null && properties.getFlux() != null) {
            imageProviderHint = blankToNull(properties.getFlux().getProviderKey());
        }
        String providerKey;
        String resolvedModelId;
        int steps;
        if (needReal) {
            com.dwcode.okxbot.video.entity.AiModelConfigEntity imgCfg;
            try {
                imgCfg = aiModelConfigService.requireEnabledImageModel(imageProviderHint, imageModelId);
            } catch (BusinessException ex) {
                if (imageModelId != null) {
                    log.warn("默认生图模型不可用 [{}]，回退库表首选: {}", imageModelId, ex.getMessage());
                    imgCfg = aiModelConfigService.requireEnabledImageModel(imageProviderHint, null);
                } else {
                    throw ex;
                }
            }
            providerKey = imgCfg.getProvider();
            resolvedModelId = imgCfg.getModelId();
            int maxSteps = imgCfg.getMaxSteps() != null && imgCfg.getMaxSteps() > 0
                    ? imgCfg.getMaxSteps() : 50;
            int defSteps = imgCfg.getDefaultSteps() != null && imgCfg.getDefaultSteps() > 0
                    ? imgCfg.getDefaultSteps()
                    : (properties.getFlux().getDefaultSteps() > 0
                    ? properties.getFlux().getDefaultSteps() : 28);
            steps = options.getSteps() != null ? options.getSteps() : defSteps;
            steps = Math.min(maxSteps, Math.max(1, steps));
        } else {
            // mock：允许无库表，用请求或默认占位
            providerKey = imageProviderHint != null ? imageProviderHint
                    : (properties.getFlux().getProviderKey() != null
                    ? properties.getFlux().getProviderKey() : "nvidia");
            resolvedModelId = imageModelId != null ? imageModelId : "mock-image";
            steps = options.getSteps() != null ? options.getSteps() : 4;
            steps = Math.min(50, Math.max(1, steps));
        }

        // —— 润色 Chat 模型（可选，capability=chat）——
        boolean enhance = Boolean.TRUE.equals(options.getEnhancePrompt());
        String llmProvider = null;
        String llmModel = null;
        if (enhance && !"off".equalsIgnoreCase(properties.getSteps().getEnhance())
                && !properties.isMockPipeline()) {
            LlmPair llm = resolveEnhanceLlm(options.getLlmProvider(), options.getLlmModel());
            llmProvider = llm.provider;
            llmModel = llm.model;
        }

        String negative = blankToNull(options.getNegativePrompt());
        if (negative == null) {
            negative = blankToNull(request.getNegativePrompt());
        }

        ImgGenTaskEntity entity = new ImgGenTaskEntity();
        entity.setUserId(SecurityUtils.requireCurrentUserId());
        entity.setPrompt(prompt);
        entity.setTitle(deriveTitle(prompt));
        entity.setNegativePrompt(negative);
        entity.setStatus(ImgGenTaskStatus.PENDING.name());
        entity.setCurrentStep("排队中");
        entity.setProgress(0);
        entity.setProvider(providerKey);
        entity.setModel(resolvedModelId);
        entity.setAspectRatio(aspect);
        entity.setWidth(size.width());
        entity.setHeight(size.height());
        entity.setSteps(steps);
        entity.setN(n);
        entity.setSeed(options.getSeed());
        entity.setEnhanceEnabled(enhance ? 1 : 0);
        entity.setLlmProvider(llmProvider);
        entity.setLlmModel(llmModel);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(entity);

        log.info("创建 imggen 任务: id={} imageModel={}/{} aspect={} n={} enhance={} llm={}/{}",
                entity.getId(), providerKey, resolvedModelId, aspect, n, enhance, llmProvider, llmModel);
        taskScheduler.notifyPending();
        eventPublisher.publishEntity(entity, ImgGenTaskEventPublisher.TYPE_CREATED);
        return toResponse(entity);
    }

    /**
     * 生图模型目录（数据库 capability=image，前端下拉）。
     */
    public List<ImgGenImageModelResponse> listImageModels() {
        var entities = aiModelConfigService.listEnabledImageEntities();
        List<ImgGenImageModelResponse> list = new ArrayList<>();
        boolean first = true;
        for (var e : entities) {
            list.add(ImgGenImageModelResponse.builder()
                    .id(e.getModelId())
                    .name(e.getModelName() != null ? e.getModelName() : e.getModelId())
                    .provider(e.getProvider())
                    .invokeUrl(e.getInvokeUrl())
                    .defaultSteps(e.getDefaultSteps() != null ? e.getDefaultSteps() : 4)
                    .maxSteps(e.getMaxSteps() != null ? e.getMaxSteps() : 50)
                    .protocol(e.getProtocol())
                    .description(e.getRemark())
                    .defaultModel(first)
                    .build());
            first = false;
        }
        return list;
    }

    public ImgGenTaskResponse getTask(Long taskId) {
        return toResponse(requireOwnedTask(taskId));
    }

    public ImgGenTaskPageResponse listTasks(int page, int size, String status) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Long userId = SecurityUtils.requireCurrentUserId();

        LambdaQueryWrapper<ImgGenTaskEntity> q = new LambdaQueryWrapper<ImgGenTaskEntity>()
                .eq(ImgGenTaskEntity::getUserId, userId)
                .orderByDesc(ImgGenTaskEntity::getCreatedAt);
        if (status != null && !status.isBlank()) {
            ImgGenTaskStatus st = ImgGenTaskStatus.from(status);
            if (st == null) {
                throw new BusinessException(400, "无效 status: " + status);
            }
            q.eq(ImgGenTaskEntity::getStatus, st.name());
        }

        Page<ImgGenTaskEntity> mpPage = new Page<>(safePage + 1L, safeSize);
        Page<ImgGenTaskEntity> result = taskMapper.selectPage(mpPage, q);
        List<ImgGenTaskResponse> items = result.getRecords().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return ImgGenTaskPageResponse.builder()
                .items(items)
                .total(result.getTotal())
                .page(safePage)
                .size(safeSize)
                .build();
    }

    public ImgGenTaskResponse cancelTask(Long taskId) {
        ImgGenTaskEntity entity = requireOwnedTask(taskId);
        String status = entity.getStatus();
        if (ImgGenTaskStatus.SUCCESS.name().equals(status)
                || ImgGenTaskStatus.FAILED.name().equals(status)
                || ImgGenTaskStatus.CANCELLED.name().equals(status)
                || ImgGenTaskStatus.PAUSED.name().equals(status)) {
            throw new BusinessException(400, "当前状态不可取消: " + status);
        }
        if (ImgGenTaskStatus.PENDING.name().equals(status)) {
            entity.setStatus(ImgGenTaskStatus.CANCELLED.name());
            entity.setCurrentStep("已取消（未开始）");
            entity.setErrorMessage("");
            entity.setFinishedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(entity);
            taskScheduler.markFinished(taskId);
            eventPublisher.publishEntity(entity, ImgGenTaskEventPublisher.TYPE_STATUS);
            return toResponse(entity);
        }
        taskScheduler.requestCancel(taskId);
        entity.setCurrentStep("取消中…");
        entity.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(entity);
        eventPublisher.publishEntity(entity, ImgGenTaskEventPublisher.TYPE_STATUS);
        taskScheduler.tryStartNext();
        return toResponse(entity);
    }

    public ImgGenTaskResponse pauseTask(Long taskId) {
        ImgGenTaskEntity entity = requireOwnedTask(taskId);
        String status = entity.getStatus();
        if (!ImgGenTaskStatus.PENDING.name().equals(status)
                && !ImgGenTaskStatus.PROMPT_ENHANCING.name().equals(status)
                && !ImgGenTaskStatus.GENERATING.name().equals(status)) {
            throw new BusinessException(400, "仅排队中或进行中的任务可暂停");
        }
        if (ImgGenTaskStatus.PENDING.name().equals(status)) {
            entity.setStatus(ImgGenTaskStatus.PAUSED.name());
            entity.setCurrentStep("已暂停（未开始）");
            entity.setErrorMessage("");
            entity.setFinishedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(entity);
            taskScheduler.markFinished(taskId);
            eventPublisher.publishEntity(entity, ImgGenTaskEventPublisher.TYPE_STATUS);
            return toResponse(entity);
        }
        taskScheduler.requestPause(taskId);
        entity.setStatus(ImgGenTaskStatus.PAUSED.name());
        entity.setCurrentStep("暂停中，等待当前步骤结束…");
        entity.setErrorMessage("");
        entity.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(entity);
        eventPublisher.publishEntity(entity, ImgGenTaskEventPublisher.TYPE_STATUS);
        taskScheduler.tryStartNext();
        return toResponse(entity);
    }

    public ImgGenTaskResponse retryTask(Long taskId, ImgGenRetryRequest request) {
        ImgGenTaskEntity entity = requireOwnedTask(taskId);
        String status = entity.getStatus() == null ? "" : entity.getStatus().trim().toUpperCase();
        if (!RETRYABLE.contains(status)) {
            throw new BusinessException(400, "当前状态不可重试: " + entity.getStatus());
        }

        if (request != null) {
            if (request.getSeed() != null) {
                entity.setSeed(request.getSeed());
            }
            // 切换生图模型（库表）
            String imgModel = blankToNull(request.getImageModel());
            String imgProvider = blankToNull(request.getImageProvider());
            if (imgModel != null || imgProvider != null) {
                boolean needReal = !properties.isMockPipeline()
                        && !"mock".equalsIgnoreCase(properties.getSteps().getGenerate());
                if (needReal) {
                    var cfg = aiModelConfigService.requireEnabledImageModel(
                            imgProvider != null ? imgProvider : entity.getProvider(),
                            imgModel != null ? imgModel : entity.getModel());
                    entity.setProvider(cfg.getProvider());
                    entity.setModel(cfg.getModelId());
                    if (cfg.getDefaultSteps() != null && cfg.getDefaultSteps() > 0) {
                        entity.setSteps(cfg.getDefaultSteps());
                    }
                } else {
                    if (imgModel != null) {
                        entity.setModel(imgModel);
                    }
                    if (imgProvider != null) {
                        entity.setProvider(imgProvider);
                    }
                }
            }
            if (request.getEnhancePrompt() != null) {
                entity.setEnhanceEnabled(Boolean.TRUE.equals(request.getEnhancePrompt()) ? 1 : 0);
            }
            // 切换润色 Chat 模型
            boolean wantEnhance = entity.getEnhanceEnabled() != null && entity.getEnhanceEnabled() == 1;
            String p = blankToNull(request.getLlmProvider());
            String m = blankToNull(request.getLlmModel());
            if (wantEnhance && (p != null || m != null
                    || entity.getLlmProvider() == null || entity.getLlmModel() == null)) {
                LlmPair llm = resolveEnhanceLlm(
                        p != null ? p : entity.getLlmProvider(),
                        m != null ? m : entity.getLlmModel());
                entity.setLlmProvider(llm.provider);
                entity.setLlmModel(llm.model);
            } else if (!wantEnhance) {
                // 关闭润色时不必清历史模型字段，重开时可复用
            }
        }

        try {
            storageService.deleteTaskDir(String.valueOf(taskId));
        } catch (Exception e) {
            log.warn("重试前清理目录失败: {}", e.getMessage());
        }

        taskScheduler.clearCancelRequest(taskId);
        taskScheduler.clearPauseRequest(taskId);
        entity.setStatus(ImgGenTaskStatus.PENDING.name());
        entity.setCurrentStep("重试排队中");
        entity.setProgress(0);
        entity.setErrorMessage("");
        entity.setEnhancedPrompt(null);
        entity.setResultJson(null);
        entity.setCoverPath(null);
        entity.setEnhanceDurationMs(null);
        entity.setGenerateDurationMs(null);
        entity.setTotalDurationMs(null);
        entity.setStartedAt(null);
        entity.setFinishedAt(null);
        entity.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(entity);
        eventPublisher.publishEntity(entity, ImgGenTaskEventPublisher.TYPE_STATUS);
        taskScheduler.notifyPending();
        return toResponse(entity);
    }

    public void deleteTask(Long taskId) {
        ImgGenTaskEntity entity = requireOwnedTask(taskId);
        Long ownerId = entity.getUserId();
        String status = entity.getStatus();
        if (ImgGenTaskStatus.PENDING.name().equals(status)
                || ImgGenTaskStatus.PROMPT_ENHANCING.name().equals(status)
                || ImgGenTaskStatus.GENERATING.name().equals(status)) {
            taskScheduler.requestCancel(taskId);
            taskScheduler.requestPause(taskId);
        }
        if (properties.isCleanupOnDelete()) {
            storageService.deleteTaskDir(String.valueOf(taskId));
        }
        int rows = taskMapper.deleteById(taskId);
        if (rows <= 0) {
            throw new BusinessException(404, "任务不存在或已删除");
        }
        taskScheduler.markFinished(taskId);
        eventPublisher.publishDeleted(ownerId, taskId);
    }

    public ResponseEntity<Resource> openMedia(Long taskId, String fileName) {
        ImgGenTaskEntity entity = requireOwnedTask(taskId);
        if (fileName == null || fileName.isBlank()
                || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw new BusinessException(400, "非法文件名");
        }
        Path workRoot = storageService.resolveWorkRoot();
        Path taskDir = storageService.resolveTaskDir(String.valueOf(taskId));
        Path file = taskDir.resolve("outputs").resolve(fileName).normalize();
        if (!file.startsWith(workRoot) || !file.startsWith(taskDir)) {
            throw new BusinessException(403, "非法路径");
        }
        if (!Files.isRegularFile(file)) {
            throw new BusinessException(404, "文件不存在");
        }
        String lower = fileName.toLowerCase();
        MediaType mediaType = MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            mediaType = MediaType.IMAGE_JPEG;
        } else if (lower.endsWith(".webp")) {
            mediaType = MediaType.parseMediaType("image/webp");
        }
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
                .body(resource);
    }

    private ImgGenTaskEntity requireOwnedTask(Long taskId) {
        ImgGenTaskEntity entity = taskMapper.selectById(taskId);
        if (entity == null) {
            throw new BusinessException(404, "任务不存在: " + taskId);
        }
        Long userId = SecurityUtils.requireCurrentUserId();
        if (entity.getUserId() != null && !entity.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问该任务");
        }
        return entity;
    }

    private ImgGenTaskResponse toResponse(ImgGenTaskEntity e) {
        boolean outputAvailable = e.getCoverPath() != null
                && !e.getCoverPath().isBlank()
                && Files.isRegularFile(Path.of(e.getCoverPath()));
        List<ImgGenTaskResponse.ImageFileDto> images = parseImages(e);
        return ImgGenTaskResponse.builder()
                .id(String.valueOf(e.getId()))
                .title(e.getTitle())
                .prompt(e.getPrompt())
                .enhancedPrompt(e.getEnhancedPrompt())
                .negativePrompt(e.getNegativePrompt())
                .status(e.getStatus())
                .currentStep(e.getCurrentStep())
                .progress(e.getProgress() != null ? e.getProgress() : 0)
                .provider(e.getProvider())
                .model(e.getModel())
                .aspectRatio(e.getAspectRatio())
                .width(e.getWidth())
                .height(e.getHeight())
                .steps(e.getSteps())
                .n(e.getN())
                .seed(e.getSeed())
                .enhanceEnabled(e.getEnhanceEnabled() != null && e.getEnhanceEnabled() == 1)
                .llmProvider(e.getLlmProvider())
                .llmModel(e.getLlmModel())
                .errorMessage(e.getErrorMessage() == null ? "" : e.getErrorMessage())
                .outputAvailable(outputAvailable)
                .images(images)
                .enhanceDurationMs(e.getEnhanceDurationMs())
                .generateDurationMs(e.getGenerateDurationMs())
                .totalDurationMs(e.getTotalDurationMs())
                .startedAt(formatTime(e.getStartedAt()))
                .finishedAt(formatTime(e.getFinishedAt()))
                .createdAt(formatTime(e.getCreatedAt()))
                .updatedAt(formatTime(e.getUpdatedAt()))
                .build();
    }

    private List<ImgGenTaskResponse.ImageFileDto> parseImages(ImgGenTaskEntity e) {
        List<ImgGenTaskResponse.ImageFileDto> list = new ArrayList<>();
        if (e.getResultJson() == null || e.getResultJson().isBlank()) {
            return list;
        }
        try {
            JsonNode root = objectMapper.readTree(e.getResultJson());
            JsonNode arr = root.get("images");
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    String path = n.has("path") ? n.get("path").asText() : null;
                    String fileName = path != null && path.contains("/")
                            ? path.substring(path.lastIndexOf('/') + 1)
                            : path;
                    list.add(ImgGenTaskResponse.ImageFileDto.builder()
                            .index(n.has("index") ? n.get("index").asInt() : list.size() + 1)
                            .path(path)
                            .mediaUrl(fileName != null
                                    ? "/api/v1/imggen/tasks/" + e.getId() + "/media/" + fileName
                                    : null)
                            .width(n.has("width") ? n.get("width").asInt() : null)
                            .height(n.has("height") ? n.get("height").asInt() : null)
                            .seed(n.has("seed") && !n.get("seed").isNull() ? n.get("seed").asLong() : null)
                            .build());
                }
            }
        } catch (Exception ex) {
            log.debug("解析 result_json 失败: {}", ex.getMessage());
        }
        return list;
    }

    private record LlmPair(String provider, String model) {
    }

    /**
     * 解析润色用 Chat 模型：请求指定 → yml 默认 → 供应商首个启用模型。
     */
    private LlmPair resolveEnhanceLlm(String providerKey, String modelId) {
        String llmProvider = blankToNull(providerKey);
        if (llmProvider == null) {
            llmProvider = blankToNull(properties.getPromptEnhance().getProvider());
        }
        if (llmProvider == null) {
            llmProvider = resolveDefaultProviderKey();
        }
        if (llmProvider == null) {
            throw new BusinessException(400, "启用润色需选择 Chat 模型供应商");
        }
        ProviderConfig pc = aiProperties.getProvider(llmProvider);
        if (pc == null || pc.getApiKey() == null || pc.getApiKey().isBlank()) {
            throw new BusinessException(400, "润色 LLM 供应商不可用或未配置 api-key: " + llmProvider);
        }
        String llmModel = blankToNull(modelId);
        if (llmModel == null) {
            llmModel = blankToNull(properties.getPromptEnhance().getModel());
        }
        if (llmModel == null) {
            llmModel = aiModelConfigService.firstEnabledModelId(llmProvider);
        }
        if (llmModel == null) {
            throw new BusinessException(400, "未配置可用润色模型，请在「模型管理」中添加 Chat 模型");
        }
        return new LlmPair(llmProvider, llmModel);
    }

    private String resolveDefaultProviderKey() {
        ProviderConfig def = aiProperties.getDefaultProvider();
        if (def != null) {
            for (Map.Entry<String, ProviderConfig> e : aiProperties.getProviders().entrySet()) {
                if (e.getValue() == def || (e.getValue().getName() != null
                        && e.getValue().getName().equals(def.getName()))) {
                    return e.getKey();
                }
            }
        }
        if (!aiProperties.getAllAvailableProviders().isEmpty()) {
            return aiProperties.getAllAvailableProviders().get(0).getKey();
        }
        return null;
    }

    private static String deriveTitle(String prompt) {
        String t = prompt.replaceAll("\\s+", " ").trim();
        return t.length() <= 40 ? t : t.substring(0, 40) + "…";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String formatTime(LocalDateTime t) {
        return t == null ? null : DT_FMT.format(t);
    }
}
