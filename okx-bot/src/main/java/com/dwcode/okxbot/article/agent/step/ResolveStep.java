package com.dwcode.okxbot.article.agent.step;

import com.dwcode.okxbot.article.agent.ArticleFetchPolicy;
import com.dwcode.okxbot.article.entity.ArticleTaskEntity;
import com.dwcode.okxbot.article.enums.ArticleTaskStatus;
import org.springframework.stereotype.Component;

@Component
public class ResolveStep implements ArticlePipelineStep {

    @Override
    public String name() {
        return "resolve";
    }

    @Override
    public ArticleTaskStatus runningStatus() {
        return ArticleTaskStatus.RESOLVING;
    }

    @Override
    public String stepLabel(ArticlePipelineContext ctx) {
        return "解析链接…";
    }

    @Override
    public int progressPercent() {
        return 10;
    }

    @Override
    public boolean applies(ArticlePipelineContext ctx) {
        return true;
    }

    @Override
    public void execute(ArticlePipelineContext ctx) {
        long resolveStart = System.currentTimeMillis();
        ArticleTaskEntity task = ctx.getTask();
        boolean pasteResumeRound = task.getPasteResume() != null && task.getPasteResume() == 1;
        if (pasteResumeRound) {
            task.setPasteResume(0);
        }
        ArticleFetchPolicy.Snapshot snap = ArticleFetchPolicy.Snapshot.of(task, pasteResumeRound);
        ctx.setSnapshot(snap);

        ArticleFetchPolicy.PasteBlock block = ArticleFetchPolicy.blockWithoutPaste(snap);
        if (block != null) {
            ctx.needsPaste(block.errorCode(), block.message());
            task.setResolveDurationMs(System.currentTimeMillis() - resolveStart);
            return;
        }
        ctx.setSkipFetch(ArticleFetchPolicy.skipFetch(snap));
        task.setResolveDurationMs(System.currentTimeMillis() - resolveStart);
    }
}
