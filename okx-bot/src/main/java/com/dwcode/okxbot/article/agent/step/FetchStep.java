package com.dwcode.okxbot.article.agent.step;

import com.dwcode.okxbot.article.entity.ArticleTaskEntity;
import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import com.dwcode.okxbot.article.enums.ArticleTaskStatus;
import com.dwcode.okxbot.article.port.ArticleFetchCommand;
import com.dwcode.okxbot.article.port.ArticleFetchPort;
import com.dwcode.okxbot.article.port.ArticleFetchResult;
import com.dwcode.okxbot.article.security.ArticleSafetyException;
import com.dwcode.okxbot.article.security.UrlSafetyGuard;
import com.dwcode.okxbot.article.service.ArticleStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@RequiredArgsConstructor
public class FetchStep implements ArticlePipelineStep {

    private final UrlSafetyGuard urlSafetyGuard;
    private final ArticleFetchPort articleFetchPort;
    private final ArticleStorageService storageService;

    @Override
    public String name() {
        return "fetch";
    }

    @Override
    public ArticleTaskStatus runningStatus() {
        return ArticleTaskStatus.FETCHING;
    }

    @Override
    public String stepLabel(ArticlePipelineContext ctx) {
        return "抓取页面…";
    }

    @Override
    public int progressPercent() {
        return 25;
    }

    @Override
    public boolean applies(ArticlePipelineContext ctx) {
        return !ctx.halted() && !ctx.isSkipFetch() && ctx.getSnapshot() != null && ctx.getSnapshot().hasUrl();
    }

    @Override
    public void execute(ArticlePipelineContext ctx) {
        ArticleTaskEntity task = ctx.getTask();
        try {
            urlSafetyGuard.assertSafeUrl(task.getSourceUrl());
        } catch (Exception e) {
            String code = e instanceof ArticleSafetyException se
                    ? se.getErrorCode() : ArticleErrorCode.SSRF_BLOCKED;
            ctx.setFetchResult(ArticleFetchResult.fail(code, e.getMessage()));
            return;
        }

        long fetchStart = System.currentTimeMillis();
        ArticleFetchCommand cmd = ArticleFetchCommand.builder()
                .url(task.getSourceUrl())
                .platform(task.getPlatform())
                .supportLevel(task.getSupportLevel())
                .build();
        ArticleFetchResult fr = articleFetchPort.fetch(cmd);
        task.setFetchDurationMs(System.currentTimeMillis() - fetchStart);
        ctx.setFetchResult(fr);
        if (fr == null || !fr.isSuccess()) {
            return;
        }
        if (fr.getFinalUrl() != null && !fr.getFinalUrl().isBlank()) {
            task.setCanonicalUrl(fr.getFinalUrl());
        }
        if (fr.getTitleHint() != null && (task.getTitle() == null || task.getTitle().isBlank())) {
            task.setTitle(fr.getTitleHint());
        }
        if (fr.getAuthorHint() != null) {
            task.setAuthor(fr.getAuthorHint());
        }
        Path workDir = ctx.getWorkDir();
        if (fr.getRawHtml() != null) {
            storageService.writeText(workDir, "raw.html", fr.getRawHtml());
            task.setRawHtmlPath(workDir.resolve("raw.html").toString());
        } else if (fr.getRawText() != null) {
            storageService.writeText(workDir, "raw.txt", fr.getRawText());
        }
    }
}
