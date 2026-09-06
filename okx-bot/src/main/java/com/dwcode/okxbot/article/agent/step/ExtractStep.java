package com.dwcode.okxbot.article.agent.step;

import com.dwcode.okxbot.article.agent.ArticleFetchPolicy;
import com.dwcode.okxbot.article.entity.ArticleTaskEntity;
import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import com.dwcode.okxbot.article.enums.ArticleTaskStatus;
import com.dwcode.okxbot.article.port.ArticleExtractPort;
import com.dwcode.okxbot.article.port.ArticleFetchResult;
import com.dwcode.okxbot.article.port.MainTextDocument;
import com.dwcode.okxbot.article.service.ArticleStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExtractStep implements ArticlePipelineStep {

    private final ArticleExtractPort articleExtractPort;
    private final ArticleStorageService storageService;

    @Override
    public String name() {
        return "extract";
    }

    @Override
    public ArticleTaskStatus runningStatus() {
        return ArticleTaskStatus.EXTRACTING;
    }

    @Override
    public String stepLabel(ArticlePipelineContext ctx) {
        ArticleFetchResult fr = ctx.getFetchResult();
        boolean fetched = fr != null && fr.isSuccess();
        return fetched ? "提取正文…" : "清洗粘贴正文…";
    }

    @Override
    public int progressPercent() {
        return 40;
    }

    @Override
    public boolean applies(ArticlePipelineContext ctx) {
        return !ctx.halted();
    }

    @Override
    public void execute(ArticlePipelineContext ctx) {
        ArticleTaskEntity task = ctx.getTask();
        ArticleFetchPolicy.Snapshot snap = ctx.getSnapshot();
        ArticleFetchResult fr = ctx.getFetchResult();
        boolean fetchOk = fr != null && fr.isSuccess();
        long extractStart = System.currentTimeMillis();

        MainTextDocument doc;
        if (fetchOk) {
            doc = articleExtractPort.extract(fr);
            if (!ArticleMainText.usable(doc)) {
                applyFailureOrPaste(ctx, snap, ArticleErrorCode.EMPTY_MAIN_TEXT,
                        doc != null && doc.getUnusableReason() != null
                                ? doc.getUnusableReason() : "未能提取有效正文",
                        "EMPTY_MAIN_TEXT_USE_PASTE");
                task.setExtractDurationMs(System.currentTimeMillis() - extractStart);
                return;
            }
        } else {
            if (snap != null && snap.hasPaste()) {
                if (fr != null) {
                    String code = fr.getErrorCode() != null ? fr.getErrorCode() : ArticleErrorCode.PIPELINE_ERROR;
                    task.setDegraded(1);
                    if (ArticleErrorCode.SSRF_BLOCKED.equals(code) || ArticleErrorCode.INVALID_URL.equals(code)
                            || (code != null && code.contains("SSRF"))) {
                        task.setDegradeReason(code + "_USE_PASTE");
                    } else {
                        task.setDegradeReason("FETCH_FAILED_USE_PASTE:" + code);
                    }
                }
                doc = articleExtractPort.fromPaste(task.getPasteText(), task.getTitle());
            } else if (fr != null) {
                applyFailureOrPaste(ctx, snap,
                        fr.getErrorCode() != null ? fr.getErrorCode() : ArticleErrorCode.PIPELINE_ERROR,
                        fr.getErrorMessage() != null ? fr.getErrorMessage() : "抓取失败",
                        null);
                task.setExtractDurationMs(System.currentTimeMillis() - extractStart);
                return;
            } else {
                ctx.fail(ArticleErrorCode.EMPTY_MAIN_TEXT, "无 URL 抓取结果且无粘贴正文");
                task.setExtractDurationMs(System.currentTimeMillis() - extractStart);
                return;
            }
        }

        task.setExtractDurationMs(System.currentTimeMillis() - extractStart);
        applyDoc(ctx, doc);
    }

    private void applyFailureOrPaste(ArticlePipelineContext ctx, ArticleFetchPolicy.Snapshot snap,
                                     String code, String message, String pasteDegradeReason) {
        ArticleFetchPolicy.OnFailure action = ArticleFetchPolicy.onSourceFailure(snap);
        if (action == ArticleFetchPolicy.OnFailure.USE_PASTE) {
            ArticleTaskEntity task = ctx.getTask();
            task.setDegraded(1);
            if (pasteDegradeReason != null) {
                task.setDegradeReason(pasteDegradeReason);
            }
            MainTextDocument doc = articleExtractPort.fromPaste(task.getPasteText(), task.getTitle());
            applyDoc(ctx, doc);
            return;
        }
        if (action == ArticleFetchPolicy.OnFailure.NEEDS_PASTE) {
            ctx.needsPaste(code, message);
            return;
        }
        ctx.fail(code, message);
    }

    private void applyDoc(ArticlePipelineContext ctx, MainTextDocument doc) {
        if (!ArticleMainText.usable(doc)) {
            ctx.fail(ArticleErrorCode.EMPTY_MAIN_TEXT,
                    doc != null && doc.getUnusableReason() != null ? doc.getUnusableReason() : "正文为空");
            return;
        }
        ArticleTaskEntity task = ctx.getTask();
        if (doc.getTitle() != null && (task.getTitle() == null || task.getTitle().isBlank())) {
            task.setTitle(doc.getTitle());
        }
        if (doc.getAuthor() != null && (task.getAuthor() == null || task.getAuthor().isBlank())) {
            task.setAuthor(doc.getAuthor());
        }
        ArticleMainText.apply(task, ctx.getWorkDir(), doc.getMainText(), storageService);
        task.setQualityScore(doc.getQualityScore());
        ctx.setDocument(doc);
    }
}
