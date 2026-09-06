package com.dwcode.okxbot.article.agent.step;

import com.dwcode.okxbot.article.config.ArticleProperties;
import com.dwcode.okxbot.article.entity.ArticleTaskEntity;
import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import com.dwcode.okxbot.article.enums.ArticleTaskStatus;
import com.dwcode.okxbot.article.port.ArticleRewriteCommand;
import com.dwcode.okxbot.article.port.ArticleRewriteLlmPort;
import com.dwcode.okxbot.article.port.ArticleRewriteResult;
import com.dwcode.okxbot.article.service.ArticleStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RewriteStep implements ArticlePipelineStep {

    private final ArticleRewriteLlmPort articleRewriteLlmPort;
    private final ArticleStorageService storageService;
    private final ArticleProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "rewrite";
    }

    @Override
    public ArticleTaskStatus runningStatus() {
        return ArticleTaskStatus.LLM_REWRITE;
    }

    @Override
    public String stepLabel(ArticlePipelineContext ctx) {
        return "二次创作（LangChain4j）…";
    }

    @Override
    public int progressPercent() {
        return 85;
    }

    @Override
    public boolean applies(ArticlePipelineContext ctx) {
        if (ctx.halted() || ctx.getCoreResult() == null) {
            return false;
        }
        ArticleTaskEntity task = ctx.getTask();
        return task != null && task.getGenerateRewrite() != null && task.getGenerateRewrite() == 1;
    }

    @Override
    public void execute(ArticlePipelineContext ctx) {
        ArticleTaskEntity task = ctx.getTask();
        long rwStart = System.currentTimeMillis();
        try {
            List<String> variants = parseVariantsList(task.getRewriteVariants());
            ArticleRewriteResult rw = articleRewriteLlmPort.rewrite(
                    ctx.getCoreResult(), ctx.getDocument(),
                    ArticleRewriteCommand.builder()
                            .language(task.getLanguage())
                            .llmProvider(task.getLlmProvider())
                            .llmModel(task.getLlmModel())
                            .variants(variants)
                            .titleHint(task.getTitle())
                            .build());
            task.setRewriteJson(rw.getRawJson());
            task.setRewriteDurationMs(System.currentTimeMillis() - rwStart);
            storageService.writeText(ctx.getWorkDir(), "rewrite.json", rw.getRawJson());
        } catch (Exception re) {
            task.setRewriteDurationMs(System.currentTimeMillis() - rwStart);
            if (properties.getRewrite().isRequired()) {
                ctx.fail(ArticleErrorCode.LLM_REWRITE_FAILED, re.getMessage());
                return;
            }
            task.setDegraded(1);
            String reason = ArticleErrorCode.LLM_REWRITE_FAILED + ":"
                    + (re.getMessage() != null ? re.getMessage() : re.getClass().getSimpleName());
            task.setDegradeReason(reason.length() > 500 ? reason.substring(0, 500) : reason);
            log.warn("REWRITE 失败，degrade 后 SUCCESS: taskId={} — {}", ctx.getTaskId(), re.getMessage());
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
