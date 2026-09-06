package com.dwcode.okxbot.article.agent.step;

import com.dwcode.okxbot.article.agent.ArticleFetchPolicy;
import com.dwcode.okxbot.article.entity.ArticleTaskEntity;
import com.dwcode.okxbot.article.port.ArticleCoreResult;
import com.dwcode.okxbot.article.port.ArticleFetchResult;
import com.dwcode.okxbot.article.port.MainTextDocument;
import lombok.Data;

import java.nio.file.Path;

@Data
public class ArticlePipelineContext {

    public enum Halt {
        NEEDS_PASTE,
        FAIL
    }

    private Long taskId;
    private ArticleTaskEntity task;
    private Path workDir;
    private long pipelineStartMs;
    private ArticleFetchPolicy.Snapshot snapshot;
    private boolean skipFetch;
    private ArticleFetchResult fetchResult;
    private MainTextDocument document;
    private ArticleCoreResult coreResult;
    private Halt halt;
    private String haltCode;
    private String haltMessage;

    public void needsPaste(String code, String message) {
        this.halt = Halt.NEEDS_PASTE;
        this.haltCode = code;
        this.haltMessage = message;
    }

    public void fail(String code, String message) {
        this.halt = Halt.FAIL;
        this.haltCode = code;
        this.haltMessage = message;
    }

    public boolean halted() {
        return halt != null;
    }
}
