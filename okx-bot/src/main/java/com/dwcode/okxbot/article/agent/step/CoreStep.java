package com.dwcode.okxbot.article.agent.step;

import com.dwcode.okxbot.article.agent.ArticleFetchPolicy;
import com.dwcode.okxbot.article.entity.ArticleTaskEntity;
import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import com.dwcode.okxbot.article.enums.ArticleTaskStatus;
import com.dwcode.okxbot.article.port.ArticleCoreCommand;
import com.dwcode.okxbot.article.port.ArticleCoreLlmPort;
import com.dwcode.okxbot.article.port.ArticleCoreResult;
import com.dwcode.okxbot.article.port.MainTextDocument;
import com.dwcode.okxbot.article.service.ArticleStorageService;
import com.dwcode.okxbot.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CoreStep implements ArticlePipelineStep {

    private final ArticleCoreLlmPort articleCoreLlmPort;
    private final ArticleStorageService storageService;

    @Override
    public String name() {
        return "core";
    }

    @Override
    public ArticleTaskStatus runningStatus() {
        return ArticleTaskStatus.LLM_CORE;
    }

    @Override
    public String stepLabel(ArticlePipelineContext ctx) {
        return "提取核心内容（LangChain4j）…";
    }

    @Override
    public int progressPercent() {
        return 60;
    }

    @Override
    public boolean applies(ArticlePipelineContext ctx) {
        return !ctx.halted();
    }

    @Override
    public void execute(ArticlePipelineContext ctx) {
        ArticleTaskEntity task = ctx.getTask();
        MainTextDocument extractedDoc = ctx.getDocument();
        String fullMain = extractedDoc != null ? extractedDoc.getMainText() : null;
        if (!ArticleFetchPolicy.hasText(fullMain)) {
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
        ctx.setDocument(doc);

        long coreStart = System.currentTimeMillis();
        try {
            ArticleCoreResult core = articleCoreLlmPort.extractCore(doc, ArticleCoreCommand.builder()
                    .language(task.getLanguage())
                    .llmProvider(task.getLlmProvider())
                    .llmModel(task.getLlmModel())
                    .extractMindMap(task.getExtractMindMap() != null && task.getExtractMindMap() == 1)
                    .titleHint(task.getTitle())
                    .sourceUrl(task.getSourceUrl())
                    .platform(task.getPlatform())
                    .build());
            task.setCoreDurationMs(System.currentTimeMillis() - coreStart);
            task.setCoreJson(core.getRawJson());
            if (ArticleFetchPolicy.hasText(core.getTitle())
                    && (task.getTitle() == null || task.getTitle().isBlank())) {
                task.setTitle(core.getTitle());
            }
            storageService.writeText(ctx.getWorkDir(), "core.json", core.getRawJson());
            ctx.setCoreResult(core);
        } catch (BusinessException be) {
            task.setCoreDurationMs(System.currentTimeMillis() - coreStart);
            ctx.fail(ArticleErrorCode.LLM_CORE_FAILED,
                    be.getMessage() != null ? be.getMessage() : "CORE 失败");
        } catch (Exception e) {
            task.setCoreDurationMs(System.currentTimeMillis() - coreStart);
            ctx.fail(ArticleErrorCode.LLM_CORE_FAILED, e.getMessage());
        }
    }
}
