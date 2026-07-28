package com.dwcode.okxbot.article.agent;

import com.dwcode.okxbot.article.config.ArticleProperties;
import com.dwcode.okxbot.article.entity.ArticleTaskEntity;
import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import com.dwcode.okxbot.article.enums.ArticleSupportLevel;
import com.dwcode.okxbot.article.enums.ArticleTaskStatus;
import com.dwcode.okxbot.article.event.ArticleTaskEventPublisher;
import com.dwcode.okxbot.article.mapper.ArticleTaskMapper;
import com.dwcode.okxbot.article.port.ArticleCoreCommand;
import com.dwcode.okxbot.article.port.ArticleCoreLlmPort;
import com.dwcode.okxbot.article.port.ArticleCoreResult;
import com.dwcode.okxbot.article.port.ArticleExtractPort;
import com.dwcode.okxbot.article.port.ArticleFetchCommand;
import com.dwcode.okxbot.article.port.ArticleFetchPort;
import com.dwcode.okxbot.article.port.ArticleFetchResult;
import com.dwcode.okxbot.article.port.ArticleRewriteCommand;
import com.dwcode.okxbot.article.port.ArticleRewriteLlmPort;
import com.dwcode.okxbot.article.port.ArticleRewriteResult;
import com.dwcode.okxbot.article.port.MainTextDocument;
import com.dwcode.okxbot.article.security.UrlSafetyGuard;
import com.dwcode.okxbot.article.service.ArticleStorageService;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 文章提取流水线。
 * <p>mock-pipeline=true：假数据 SUCCESS（不模拟 NEEDS_PASTE）。
 * <p>PR-4：真实 Generic 抓取 + 正文提取 + NEEDS_PASTE。
 * <p>PR-5：CORE/REWRITE 经 {@link ArticleCoreLlmPort}/{@link ArticleRewriteLlmPort}
 * → {@code LlmChatClient} → LangChain4j。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticlePipeline {

    private static final String DISCLAIMER =
            "内容来源于公开网页或用户粘贴，AI 提取与改写可能有误，请自行核验事实与版权。";
    private static final int MAIN_TEXT_DB_CHARS = 4000;

    private final ArticleTaskMapper taskMapper;
    private final ArticleTaskScheduler taskScheduler;
    private final ArticleTaskEventPublisher eventPublisher;
    private final ArticleStorageService storageService;
    private final ArticleProperties properties;
    private final ObjectMapper objectMapper;
    private final ArticleFetchPort articleFetchPort;
    private final ArticleExtractPort articleExtractPort;
    private final UrlSafetyGuard urlSafetyGuard;
    private final ArticleCoreLlmPort articleCoreLlmPort;
    private final ArticleRewriteLlmPort articleRewriteLlmPort;

    public void run(Long taskId) {
        ArticleTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            log.error("article 任务不存在: {}", taskId);
            taskScheduler.markFinished(taskId);
            return;
        }
        ArticleTaskStatus st = ArticleTaskStatus.from(task.getStatus());
        if (st != null && st.isTerminal()) {
            taskScheduler.markFinished(taskId);
            return;
        }
        if (!ArticleTaskStatus.PENDING.name().equals(task.getStatus())) {
            log.warn("article 非 PENDING 被调度，跳过: taskId={} status={}", taskId, task.getStatus());
            taskScheduler.markFinished(taskId);
            return;
        }

        taskScheduler.markRunning(taskId);
        taskScheduler.clearCancelRequest(taskId);
        taskScheduler.clearPauseRequest(taskId);

        long pipelineStart = System.currentTimeMillis();
        task.setStartedAt(LocalDateTime.now());
        task.setFinishedAt(null);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        try {
            Path workDir = storageService.ensureTaskDir(String.valueOf(taskId));
            task.setWorkDir(workDir.toAbsolutePath().toString());
            taskMapper.updateById(task);

            if (properties.isMockPipeline()) {
                runMock(task, workDir, pipelineStart);
            } else {
                runReal(task, workDir, pipelineStart);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (taskScheduler.isPauseRequested(taskId)) {
                markPaused(task, pipelineStart);
            } else {
                markCancelled(task, pipelineStart);
            }
        } catch (Exception e) {
            if (taskScheduler.isPauseRequested(taskId)) {
                markPaused(task, pipelineStart);
            } else if (taskScheduler.isCancelRequested(taskId)) {
                markCancelled(task, pipelineStart);
            } else {
                fail(task, ArticleErrorCode.PIPELINE_ERROR, e.getMessage(), pipelineStart);
            }
        } finally {
            taskScheduler.markFinished(taskId);
        }
    }

    private void runMock(ArticleTaskEntity task, Path workDir, long pipelineStart)
            throws InterruptedException {
        Long taskId = task.getId();
        stepDelay();
        if (stopBoundary(taskId, task, pipelineStart)) {
            return;
        }
        updateStatus(task, ArticleTaskStatus.RESOLVING, "解析链接…", 10);

        stepDelay();
        if (stopBoundary(taskId, task, pipelineStart)) {
            return;
        }
        // mock 不模拟 NEEDS_PASTE
        boolean skipFetch = shouldSkipFetch(task);
        if (!skipFetch && hasText(task.getSourceUrl())) {
            updateStatus(task, ArticleTaskStatus.FETCHING, "抓取页面（mock）…", 25);
            stepDelay();
            if (stopBoundary(taskId, task, pipelineStart)) {
                return;
            }
            task.setFetchDurationMs(properties.getMockStepDelayMs());
        }

        updateStatus(task, ArticleTaskStatus.EXTRACTING, "提取正文（mock）…", 40);
        stepDelay();
        if (stopBoundary(taskId, task, pipelineStart)) {
            return;
        }

        String main = hasText(task.getPasteText())
                ? task.getPasteText().trim()
                : "【Mock】示例新闻正文：关于 " + nullTo(task.getTitle(), "未命名话题")
                + " 的核心内容摘要。来源：" + nullTo(task.getSourceUrl(), "paste");
        applyMainText(task, workDir, main);
        task.setExtractDurationMs(properties.getMockStepDelayMs());
        task.setQualityScore(0.9);

        updateStatus(task, ArticleTaskStatus.LLM_CORE, "提取核心内容（mock）…", 60);
        stepDelay();
        if (stopBoundary(taskId, task, pipelineStart)) {
            return;
        }
        String coreJson = buildStubCoreJson(task, main, true);
        task.setCoreJson(coreJson);
        task.setCoreDurationMs(properties.getMockStepDelayMs());
        storageService.writeText(workDir, "core.json", coreJson);

        if (task.getGenerateRewrite() != null && task.getGenerateRewrite() == 1) {
            updateStatus(task, ArticleTaskStatus.LLM_REWRITE, "二次创作（mock）…", 85);
            stepDelay();
            if (stopBoundary(taskId, task, pipelineStart)) {
                return;
            }
            String rewriteJson = buildStubRewriteJson(task, main, true);
            task.setRewriteJson(rewriteJson);
            task.setRewriteDurationMs(properties.getMockStepDelayMs());
            storageService.writeText(workDir, "rewrite.json", rewriteJson);
        }

        finishSuccess(task, workDir, pipelineStart);
    }

    /**
     * 真实路径：RESOLVE →（可选 FETCH）→ EXTRACT → CORE/REWRITE（LangChain4j）。
     * 优先级矩阵见设计 §7.7。
     */
    private void runReal(ArticleTaskEntity task, Path workDir, long pipelineStart)
            throws InterruptedException {
        Long taskId = task.getId();
        long resolveStart = System.currentTimeMillis();
        if (stopBoundary(taskId, task, pipelineStart)) {
            return;
        }
        updateStatus(task, ArticleTaskStatus.RESOLVING, "解析链接…", 10);

        // paste_resume 单轮消费：读出后立即清 0
        boolean pasteResumeRound = task.getPasteResume() != null && task.getPasteResume() == 1;
        if (pasteResumeRound) {
            task.setPasteResume(0);
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
        }

        boolean hasPaste = hasText(task.getPasteText());
        boolean hasUrl = hasText(task.getSourceUrl());
        boolean forcePaste = task.getForcePasteOnly() != null && task.getForcePasteOnly() == 1;
        boolean allowFallback = task.getAllowPasteFallback() == null || task.getAllowPasteFallback() == 1;
        ArticleSupportLevel sl = ArticleSupportLevel.from(task.getSupportLevel());

        // PASTE_ONLY / UNSUPPORTED 且无 paste → 零 HTTP
        if ((sl == ArticleSupportLevel.PASTE_ONLY || sl == ArticleSupportLevel.UNSUPPORTED) && !hasPaste) {
            enterNeedsPaste(task,
                    sl == ArticleSupportLevel.PASTE_ONLY
                            ? ArticleErrorCode.PLATFORM_PASTE_ONLY
                            : ArticleErrorCode.PLATFORM_UNSUPPORTED,
                    "该平台未开放自动抓取，请粘贴正文后继续",
                    pipelineStart);
            return;
        }

        boolean skipFetch = (forcePaste && hasPaste)
                || (!hasUrl && hasPaste)
                || (hasPaste && pasteResumeRound)
                || (sl == ArticleSupportLevel.PASTE_ONLY && hasPaste)
                || (sl == ArticleSupportLevel.UNSUPPORTED && hasPaste);

        task.setResolveDurationMs(System.currentTimeMillis() - resolveStart);

        MainTextDocument doc;
        if (!skipFetch && hasUrl) {
            // 再次 SSRF（创建时已检；短链跳转由 fetcher 每跳重检）
            try {
                urlSafetyGuard.assertSafeUrl(task.getSourceUrl());
            } catch (Exception e) {
                String code = e instanceof com.dwcode.okxbot.article.security.ArticleSafetyException se
                        ? se.getErrorCode() : ArticleErrorCode.SSRF_BLOCKED;
                if (hasPaste) {
                    task.setDegraded(1);
                    task.setDegradeReason(code + "_USE_PASTE");
                    doc = articleExtractPort.fromPaste(task.getPasteText(), task.getTitle());
                } else if (allowFallback) {
                    enterNeedsPaste(task, code, e.getMessage(), pipelineStart);
                    return;
                } else {
                    fail(task, code, e.getMessage(), pipelineStart);
                    return;
                }
                if (applyExtractedOrFail(task, workDir, doc, pipelineStart, taskId)) {
                    return;
                }
                runLlmAndFinish(task, workDir, pipelineStart, taskId, doc);
                return;
            }

            if (stopBoundary(taskId, task, pipelineStart)) {
                return;
            }
            updateStatus(task, ArticleTaskStatus.FETCHING, "抓取页面…", 25);
            long fetchStart = System.currentTimeMillis();
            ArticleFetchCommand cmd = ArticleFetchCommand.builder()
                    .url(task.getSourceUrl())
                    .platform(task.getPlatform())
                    .supportLevel(task.getSupportLevel())
                    .build();
            ArticleFetchResult fr = articleFetchPort.fetch(cmd);
            task.setFetchDurationMs(System.currentTimeMillis() - fetchStart);

            if (!fr.isSuccess()) {
                if (hasPaste) {
                    // url+paste 且非 force：先 FETCH 失败则立即用 paste，不进 NEEDS_PASTE
                    task.setDegraded(1);
                    task.setDegradeReason("FETCH_FAILED_USE_PASTE:" + fr.getErrorCode());
                    doc = articleExtractPort.fromPaste(task.getPasteText(), task.getTitle());
                } else if (allowFallback) {
                    enterNeedsPaste(task,
                            fr.getErrorCode() != null ? fr.getErrorCode() : ArticleErrorCode.PIPELINE_ERROR,
                            fr.getErrorMessage() != null ? fr.getErrorMessage() : "抓取失败",
                            pipelineStart);
                    return;
                } else {
                    fail(task,
                            fr.getErrorCode() != null ? fr.getErrorCode() : ArticleErrorCode.PIPELINE_ERROR,
                            fr.getErrorMessage(),
                            pipelineStart);
                    return;
                }
            } else {
                if (fr.getFinalUrl() != null && !fr.getFinalUrl().isBlank()) {
                    task.setCanonicalUrl(fr.getFinalUrl());
                }
                if (fr.getTitleHint() != null && (task.getTitle() == null || task.getTitle().isBlank())) {
                    task.setTitle(fr.getTitleHint());
                }
                if (fr.getAuthorHint() != null) {
                    task.setAuthor(fr.getAuthorHint());
                }
                // 落盘 raw
                if (fr.getRawHtml() != null) {
                    storageService.writeText(workDir, "raw.html", fr.getRawHtml());
                    task.setRawHtmlPath(workDir.resolve("raw.html").toString());
                } else if (fr.getRawText() != null) {
                    storageService.writeText(workDir, "raw.txt", fr.getRawText());
                }

                if (stopBoundary(taskId, task, pipelineStart)) {
                    return;
                }
                updateStatus(task, ArticleTaskStatus.EXTRACTING, "提取正文…", 40);
                long extractStart = System.currentTimeMillis();
                doc = articleExtractPort.extract(fr);
                task.setExtractDurationMs(System.currentTimeMillis() - extractStart);

                if (doc.isUnusable()) {
                    if (hasPaste) {
                        task.setDegraded(1);
                        task.setDegradeReason("EMPTY_MAIN_TEXT_USE_PASTE");
                        doc = articleExtractPort.fromPaste(task.getPasteText(), task.getTitle());
                    } else if (allowFallback) {
                        enterNeedsPaste(task, ArticleErrorCode.EMPTY_MAIN_TEXT,
                                doc.getUnusableReason() != null ? doc.getUnusableReason() : "未能提取有效正文",
                                pipelineStart);
                        return;
                    } else {
                        fail(task, ArticleErrorCode.EMPTY_MAIN_TEXT,
                                doc.getUnusableReason(), pipelineStart);
                        return;
                    }
                }
            }
        } else if (hasPaste) {
            if (stopBoundary(taskId, task, pipelineStart)) {
                return;
            }
            updateStatus(task, ArticleTaskStatus.EXTRACTING, "清洗粘贴正文…", 40);
            long extractStart = System.currentTimeMillis();
            doc = articleExtractPort.fromPaste(task.getPasteText(), task.getTitle());
            task.setExtractDurationMs(System.currentTimeMillis() - extractStart);
        } else {
            fail(task, ArticleErrorCode.EMPTY_MAIN_TEXT, "无 URL 抓取结果且无粘贴正文", pipelineStart);
            return;
        }

        if (applyExtractedOrFail(task, workDir, doc, pipelineStart, taskId)) {
            return;
        }
        runLlmAndFinish(task, workDir, pipelineStart, taskId, doc);
    }

    /** @return true 若已失败/需中止 */
    private boolean applyExtractedOrFail(ArticleTaskEntity task, Path workDir, MainTextDocument doc,
                                         long pipelineStart, Long taskId) throws InterruptedException {
        if (doc == null || doc.isUnusable() || !hasText(doc.getMainText())) {
            fail(task, ArticleErrorCode.EMPTY_MAIN_TEXT,
                    doc != null && doc.getUnusableReason() != null ? doc.getUnusableReason() : "正文为空",
                    pipelineStart);
            return true;
        }
        if (stopBoundary(taskId, task, pipelineStart)) {
            return true;
        }
        if (doc.getTitle() != null && (task.getTitle() == null || task.getTitle().isBlank())) {
            task.setTitle(doc.getTitle());
        }
        if (doc.getAuthor() != null && (task.getAuthor() == null || task.getAuthor().isBlank())) {
            task.setAuthor(doc.getAuthor());
        }
        applyMainText(task, workDir, doc.getMainText());
        task.setQualityScore(doc.getQualityScore());
        return false;
    }

    /**
     * PR-5：LangChain4j（经 LlmChatClient）CORE + REWRITE。
     * CORE 失败 → FAILED；REWRITE 失败且 required=false → degrade 仍 SUCCESS。
     */
    private void runLlmAndFinish(ArticleTaskEntity task, Path workDir, long pipelineStart, Long taskId,
                                 MainTextDocument extractedDoc) throws InterruptedException {
        // 优先抽取结果全文，其次 scratch/对象存储，最后 DB 截断副本
        String fullMain = extractedDoc != null ? extractedDoc.getMainText() : null;
        if (!hasText(fullMain)) {
            fullMain = storageService.readMainTextFromPath(task);
        }
        MainTextDocument doc = MainTextDocument.builder()
                .title(task.getTitle())
                .author(task.getAuthor())
                .mainText(fullMain)
                .qualityScore(task.getQualityScore() != null ? task.getQualityScore() : 0.7)
                .truncated(extractedDoc != null && extractedDoc.isTruncated())
                .source(extractedDoc != null ? extractedDoc.getSource() : "html")
                .build();

        if (stopBoundary(taskId, task, pipelineStart)) {
            return;
        }
        updateStatus(task, ArticleTaskStatus.LLM_CORE, "提取核心内容（LangChain4j）…", 60);
        long coreStart = System.currentTimeMillis();
        ArticleCoreResult core;
        try {
            core = articleCoreLlmPort.extractCore(doc, ArticleCoreCommand.builder()
                    .language(task.getLanguage())
                    .llmProvider(task.getLlmProvider())
                    .llmModel(task.getLlmModel())
                    .extractMindMap(task.getExtractMindMap() != null && task.getExtractMindMap() == 1)
                    .titleHint(task.getTitle())
                    .sourceUrl(task.getSourceUrl())
                    .platform(task.getPlatform())
                    .build());
        } catch (BusinessException be) {
            fail(task, ArticleErrorCode.LLM_CORE_FAILED,
                    be.getMessage() != null ? be.getMessage() : "CORE 失败", pipelineStart);
            return;
        } catch (Exception e) {
            fail(task, ArticleErrorCode.LLM_CORE_FAILED, e.getMessage(), pipelineStart);
            return;
        }
        task.setCoreDurationMs(System.currentTimeMillis() - coreStart);
        task.setCoreJson(core.getRawJson());
        if (hasText(core.getTitle()) && (task.getTitle() == null || task.getTitle().isBlank())) {
            task.setTitle(core.getTitle());
        }
        storageService.writeText(workDir, "core.json", core.getRawJson());
        taskMapper.updateById(task);
        eventPublisher.publishEntity(task, ArticleTaskEventPublisher.TYPE_STATUS);

        if (task.getGenerateRewrite() != null && task.getGenerateRewrite() == 1) {
            if (stopBoundary(taskId, task, pipelineStart)) {
                return;
            }
            updateStatus(task, ArticleTaskStatus.LLM_REWRITE, "二次创作（LangChain4j）…", 85);
            long rwStart = System.currentTimeMillis();
            try {
                List<String> variants = parseVariantsList(task.getRewriteVariants());
                ArticleRewriteResult rw = articleRewriteLlmPort.rewrite(core, doc,
                        ArticleRewriteCommand.builder()
                                .language(task.getLanguage())
                                .llmProvider(task.getLlmProvider())
                                .llmModel(task.getLlmModel())
                                .variants(variants)
                                .titleHint(task.getTitle())
                                .build());
                task.setRewriteJson(rw.getRawJson());
                task.setRewriteDurationMs(System.currentTimeMillis() - rwStart);
                storageService.writeText(workDir, "rewrite.json", rw.getRawJson());
            } catch (Exception re) {
                task.setRewriteDurationMs(System.currentTimeMillis() - rwStart);
                if (properties.getRewrite().isRequired()) {
                    fail(task, ArticleErrorCode.LLM_REWRITE_FAILED, re.getMessage(), pipelineStart);
                    return;
                }
                task.setDegraded(1);
                String reason = ArticleErrorCode.LLM_REWRITE_FAILED + ":"
                        + (re.getMessage() != null ? re.getMessage() : re.getClass().getSimpleName());
                task.setDegradeReason(reason.length() > 500 ? reason.substring(0, 500) : reason);
                log.warn("REWRITE 失败，degrade 后 SUCCESS: taskId={} — {}", taskId, re.getMessage());
            }
        }
        finishSuccess(task, workDir, pipelineStart);
    }

    private List<String> parseVariantsList(String raw) {
        if (raw == null || raw.isBlank()) {
            return properties.getRewrite().getDefaultVariants();
        }
        try {
            return objectMapper.readValue(raw,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return properties.getRewrite().getDefaultVariants();
        }
    }

    private boolean shouldSkipFetch(ArticleTaskEntity task) {
        boolean hasPaste = hasText(task.getPasteText());
        boolean hasUrl = hasText(task.getSourceUrl());
        boolean forcePaste = task.getForcePasteOnly() != null && task.getForcePasteOnly() == 1;
        boolean pasteResume = task.getPasteResume() != null && task.getPasteResume() == 1;
        return forcePaste && hasPaste || (!hasUrl && hasPaste) || (hasPaste && pasteResume);
    }

    private void applyMainText(ArticleTaskEntity task, Path workDir, String main) {
        int fullLen = main.length();
        task.setMainTextChars(fullLen);
        String forDb = main.length() > MAIN_TEXT_DB_CHARS
                ? main.substring(0, MAIN_TEXT_DB_CHARS)
                : main;
        task.setMainText(forDb);
        storageService.writeText(workDir, "main.txt", main);
        if (task.getTitle() == null || task.getTitle().isBlank()) {
            task.setTitle(deriveTitle(main));
        }
    }

    private void finishSuccess(ArticleTaskEntity task, Path workDir, long pipelineStart) {
        clearPasteResumeFlag(task);
        try {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("disclaimer", DISCLAIMER);
            if (task.getCoreJson() != null) {
                result.set("core", objectMapper.readTree(task.getCoreJson()));
            }
            if (task.getRewriteJson() != null) {
                result.set("rewrite", objectMapper.readTree(task.getRewriteJson()));
            }
            result.put("title", task.getTitle());
            result.put("platform", task.getPlatform());
            String resultJson = objectMapper.writeValueAsString(result);
            task.setResultJson(resultJson);
            storageService.writeText(workDir, "result.json", resultJson);
        } catch (Exception e) {
            log.warn("组装 result_json 失败: {}", e.getMessage());
        }
        storageService.persistOutputs(task, workDir);

        task.setStatus(ArticleTaskStatus.SUCCESS.name());
        task.setCurrentStep("完成");
        task.setProgress(100);
        task.setErrorCode(null);
        task.setErrorMessage("");
        task.setFinishedAt(LocalDateTime.now());
        task.setTotalDurationMs(System.currentTimeMillis() - pipelineStart);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        eventPublisher.publishEntity(task, ArticleTaskEventPublisher.TYPE_STATUS);
        log.info("article 完成: taskId={} total={}ms", task.getId(), task.getTotalDurationMs());
    }

    private void enterNeedsPaste(ArticleTaskEntity task, String code, String msg, long pipelineStart) {
        clearPasteResumeFlag(task);
        task.setStatus(ArticleTaskStatus.NEEDS_PASTE.name());
        task.setProgress(35);
        task.setCurrentStep("需要粘贴正文");
        task.setErrorCode(code);
        task.setErrorMessage(msg);
        task.setDegraded(1);
        task.setDegradeReason(code + ":" + msg);
        task.setFinishedAt(LocalDateTime.now());
        task.setTotalDurationMs(System.currentTimeMillis() - pipelineStart);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        eventPublisher.publishEntity(task, ArticleTaskEventPublisher.TYPE_STATUS);
    }

    private void fail(ArticleTaskEntity task, String code, String msg, long pipelineStart) {
        clearPasteResumeFlag(task);
        task.setStatus(ArticleTaskStatus.FAILED.name());
        task.setCurrentStep("失败");
        task.setErrorCode(code);
        task.setErrorMessage(msg != null && msg.length() > 1000 ? msg.substring(0, 1000) : msg);
        task.setFinishedAt(LocalDateTime.now());
        task.setTotalDurationMs(System.currentTimeMillis() - pipelineStart);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        eventPublisher.publishEntity(task, ArticleTaskEventPublisher.TYPE_STATUS);
    }

    private void markCancelled(ArticleTaskEntity task, long pipelineStart) {
        clearPasteResumeFlag(task);
        task.setStatus(ArticleTaskStatus.CANCELLED.name());
        task.setCurrentStep("已取消");
        task.setErrorMessage("");
        task.setFinishedAt(LocalDateTime.now());
        task.setTotalDurationMs(System.currentTimeMillis() - pipelineStart);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        eventPublisher.publishEntity(task, ArticleTaskEventPublisher.TYPE_STATUS);
    }

    private void markPaused(ArticleTaskEntity task, long pipelineStart) {
        clearPasteResumeFlag(task);
        task.setStatus(ArticleTaskStatus.PAUSED.name());
        task.setCurrentStep("已暂停");
        task.setErrorMessage("");
        task.setFinishedAt(LocalDateTime.now());
        task.setTotalDurationMs(System.currentTimeMillis() - pipelineStart);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        eventPublisher.publishEntity(task, ArticleTaskEventPublisher.TYPE_STATUS);
    }

    private boolean stopBoundary(Long taskId, ArticleTaskEntity task, long pipelineStart) {
        if (taskScheduler.isCancelRequested(taskId)) {
            markCancelled(task, pipelineStart);
            return true;
        }
        if (taskScheduler.isPauseRequested(taskId)) {
            markPaused(task, pipelineStart);
            return true;
        }
        return false;
    }

    private void updateStatus(ArticleTaskEntity task, ArticleTaskStatus status,
                              String step, int progress) {
        task.setStatus(status.name());
        task.setCurrentStep(step);
        task.setProgress(progress);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        eventPublisher.publishEntity(task, ArticleTaskEventPublisher.TYPE_STATUS);
    }

    private void stepDelay() throws InterruptedException {
        long ms = Math.max(0, properties.getMockStepDelayMs());
        if (ms > 0) {
            Thread.sleep(ms);
        }
    }

    private void clearPasteResumeFlag(ArticleTaskEntity task) {
        if (task.getPasteResume() != null && task.getPasteResume() != 0) {
            task.setPasteResume(0);
        }
    }

    private String buildStubCoreJson(ArticleTaskEntity task, String main, boolean mock) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("title", nullTo(task.getTitle(), deriveTitle(main)));
            String summary = main.length() > 200 ? main.substring(0, 200) + "…" : main;
            root.put("summary", summary);
            ArrayNode kps = root.putArray("keyPoints");
            String[] lines = main.split("[\\n。！？.!?]+");
            int n = 0;
            for (String line : lines) {
                String t = line.trim();
                if (t.length() < 8) {
                    continue;
                }
                ObjectNode kp = kps.addObject();
                kp.put("point", t.length() > 120 ? t.substring(0, 120) + "…" : t);
                n++;
                if (n >= 5) {
                    break;
                }
            }
            if (n == 0) {
                kps.addObject().put("point", summary);
            }
            root.putArray("entities");
            root.putArray("timeline");
            if (task.getExtractMindMap() != null && task.getExtractMindMap() == 1) {
                root.put("mindMapMarkdown", "# " + root.get("title").asText() + "\n- 要点\n");
            }
            root.put("stub", !mock);
            root.put("mock", mock);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"title\":\"\",\"summary\":\"" + escape(main) + "\",\"keyPoints\":[]}";
        }
    }

    private String buildStubRewriteJson(ArticleTaskEntity task, String main, boolean mock) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode variants = root.putArray("variants");
            List<String> ids = parseVariantsList(task.getRewriteVariants());
            String snippet = main.length() > 300 ? main.substring(0, 300) + "…" : main;
            for (String id : ids) {
                ObjectNode v = variants.addObject();
                v.put("id", id);
                v.put("title", id);
                v.put("content", "【" + (mock ? "Mock" : "Stub") + " " + id + "】\n" + snippet);
            }
            root.put("stub", !mock);
            root.put("mock", mock);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"variants\":[]}";
        }
    }

    private static String deriveTitle(String main) {
        if (main == null || main.isBlank()) {
            return "未命名文章";
        }
        String t = main.trim().replaceAll("\\s+", " ");
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String nullTo(String s, String d) {
        return s == null || s.isBlank() ? d : s;
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
