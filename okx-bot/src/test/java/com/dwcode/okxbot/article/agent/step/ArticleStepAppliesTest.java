package com.dwcode.okxbot.article.agent.step;

import com.dwcode.okxbot.article.agent.ArticleFetchPolicy;
import com.dwcode.okxbot.article.entity.ArticleTaskEntity;
import com.dwcode.okxbot.article.enums.ArticleSupportLevel;
import com.dwcode.okxbot.article.port.ArticleCoreResult;
import com.dwcode.okxbot.article.port.ArticleFetchResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleStepAppliesTest {

    @Test
    void fetchSkippedWhenPolicySaysSo() {
        FetchStep step = new FetchStep(null, null, null);
        ArticlePipelineContext ctx = new ArticlePipelineContext();
        ctx.setSkipFetch(true);
        ctx.setSnapshot(new ArticleFetchPolicy.Snapshot(
                true, true, true, true, false, ArticleSupportLevel.FULL));
        assertFalse(step.applies(ctx));
    }

    @Test
    void fetchRunsWhenUrlAndNotSkip() {
        FetchStep step = new FetchStep(null, null, null);
        ArticlePipelineContext ctx = new ArticlePipelineContext();
        ctx.setSkipFetch(false);
        ctx.setSnapshot(new ArticleFetchPolicy.Snapshot(
                false, true, false, true, false, ArticleSupportLevel.FULL));
        assertTrue(step.applies(ctx));
    }

    @Test
    void rewriteOnlyWhenRequestedAndCoreOk() {
        RewriteStep step = new RewriteStep(null, null, null, null);
        ArticlePipelineContext ctx = new ArticlePipelineContext();
        ArticleTaskEntity task = new ArticleTaskEntity();
        task.setGenerateRewrite(0);
        ctx.setTask(task);
        ctx.setCoreResult(ArticleCoreResult.builder().rawJson("{}").build());
        assertFalse(step.applies(ctx));

        task.setGenerateRewrite(1);
        assertTrue(step.applies(ctx));

        ctx.fail("X", "y");
        assertFalse(step.applies(ctx));
    }

    @Test
    void extractSkippedWhenHalted() {
        ExtractStep step = new ExtractStep(null, null);
        ArticlePipelineContext ctx = new ArticlePipelineContext();
        ctx.needsPaste("PLATFORM_PASTE_ONLY", "paste");
        assertFalse(step.applies(ctx));
    }

    @Test
    void fetchSuccessChangesExtractLabel() {
        ExtractStep step = new ExtractStep(null, null);
        ArticlePipelineContext ctx = new ArticlePipelineContext();
        assertTrue(step.stepLabel(ctx).contains("粘贴"));
        ctx.setFetchResult(ArticleFetchResult.builder().success(true).rawHtml("<p>a</p>").build());
        assertTrue(step.stepLabel(ctx).contains("提取正文"));
    }
}
