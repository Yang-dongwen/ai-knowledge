package com.dwcode.okxbot.article.agent;

import com.dwcode.okxbot.article.agent.step.ArticleMainText;
import com.dwcode.okxbot.article.agent.step.ArticlePipelineContext;
import com.dwcode.okxbot.article.agent.step.ArticlePipelineStep;
import com.dwcode.okxbot.article.agent.step.CoreStep;
import com.dwcode.okxbot.article.agent.step.ExtractStep;
import com.dwcode.okxbot.article.agent.step.FetchStep;
import com.dwcode.okxbot.article.agent.step.ResolveStep;
import com.dwcode.okxbot.article.agent.step.RewriteStep;
import com.dwcode.okxbot.article.config.ArticleProperties;
import com.dwcode.okxbot.article.entity.ArticleTaskEntity;
import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import com.dwcode.okxbot.article.enums.ArticleTaskStatus;
import com.dwcode.okxbot.article.event.ArticleTaskEventPublisher;
import com.dwcode.okxbot.article.mapper.ArticleTaskMapper;
import com.dwcode.okxbot.article.service.ArticleStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 文章提取流水线外壳：暂停/取消、NEEDS_PASTE、落库、SSE。
 * 真实路径：Resolve → Fetch → Extract → Core → Rewrite。
 */
@Slf4j
@Component
public class ArticlePipeline {

    private static final String DISCLAIMER =
            "内容来源于公开网页或用户粘贴，AI 提取与改写可能有误，请自行核验事实与版权。";

    private final ArticleTaskMapper taskMapper;
    private final ArticleTaskScheduler taskScheduler;
    private final ArticleTaskEventPublisher eventPublisher;
    private final ArticleStorageService storageService;
    private final ArticleProperties properties;
    private final ObjectMapper objectMapper;
    private final List<ArticlePipelineStep> realSteps;

    public ArticlePipeline(ArticleTaskMapper taskMapper,
                           ArticleTaskScheduler taskScheduler,
                           ArticleTaskEventPublisher eventPublisher,
                           ArticleStorageService storageService,
                           ArticleProperties properties,
                           ObjectMapper objectMapper,
                           ResolveStep resolveStep,
                           FetchStep fetchStep,
                           ExtractStep extractStep,
                           CoreStep coreStep,
                           RewriteStep rewriteStep) {
        this.taskMapper = taskMapper;
        this.taskScheduler = taskScheduler;
        this.eventPublisher = eventPublisher;
        this.storageService = storageService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.realSteps = List.of(resolveStep, fetchStep, extractStep, coreStep, rewriteStep);
    }

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

    private void runReal(ArticleTaskEntity task, Path workDir, long pipelineStart)
            throws Exception {
        ArticlePipelineContext ctx = new ArticlePipelineContext();
        ctx.setTaskId(task.getId());
        ctx.setTask(task);
        ctx.setWorkDir(workDir);
        ctx.setPipelineStartMs(pipelineStart);

        for (ArticlePipelineStep step : realSteps) {
            if (!step.applies(ctx)) {
                continue;
            }
            if (stopBoundary(task.getId(), task, pipelineStart)) {
                return;
            }
            updateStatus(task, step.runningStatus(), step.stepLabel(ctx), step.progressPercent());
            step.execute(ctx);
            persist(task);
            if (ctx.halted()) {
                if (ctx.getHalt() == ArticlePipelineContext.Halt.NEEDS_PASTE) {
                    enterNeedsPaste(task, ctx.getHaltCode(), ctx.getHaltMessage(), pipelineStart);
                } else {
                    fail(task, ctx.getHaltCode(), ctx.getHaltMessage(), pipelineStart);
                }
                return;
            }
            if (stopBoundary(task.getId(), task, pipelineStart)) {
                return;
            }
        }
        finishSuccess(task, workDir, pipelineStart);
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
        boolean pasteResume = task.getPasteResume() != null && task.getPasteResume() == 1;
        boolean skipFetch = ArticleFetchPolicy.skipFetch(
                ArticleFetchPolicy.Snapshot.of(task, pasteResume));
        if (!skipFetch && ArticleFetchPolicy.hasText(task.getSourceUrl())) {
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

        String main = ArticleFetchPolicy.hasText(task.getPasteText())
                ? task.getPasteText().trim()
                : "【Mock】示例新闻正文：关于 " + ArticleMainText.nullTo(task.getTitle(), "未命名话题")
                + " 的核心内容摘要。来源：" + ArticleMainText.nullTo(task.getSourceUrl(), "paste");
        ArticleMainText.apply(task, workDir, main, storageService);
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

    private void persist(ArticleTaskEntity task) {
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        eventPublisher.publishEntity(task, ArticleTaskEventPublisher.TYPE_STATUS);
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
            root.put("title", ArticleMainText.nullTo(task.getTitle(), ArticleMainText.deriveTitle(main)));
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
            return "{\"title\":\"\",\"summary\":\"" + ArticleMainText.escape(main) + "\",\"keyPoints\":[]}";
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
}
