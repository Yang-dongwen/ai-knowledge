package com.dwcode.okxbot.article.agent;

import com.dwcode.okxbot.article.entity.ArticleTaskEntity;
import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import com.dwcode.okxbot.article.enums.ArticleSupportLevel;

/**
 * 要不要发 HTTP、失败是粘贴 / NEEDS_PASTE / 失败。纯决策，不碰 IO。
 */
public final class ArticleFetchPolicy {

    public enum OnFailure {
        USE_PASTE,
        NEEDS_PASTE,
        FAIL
    }

    public record Snapshot(
            boolean hasPaste,
            boolean hasUrl,
            boolean forcePaste,
            boolean allowFallback,
            boolean pasteResumeRound,
            ArticleSupportLevel supportLevel
    ) {
        public static Snapshot of(ArticleTaskEntity task, boolean pasteResumeRound) {
            return new Snapshot(
                    hasText(task.getPasteText()),
                    hasText(task.getSourceUrl()),
                    task.getForcePasteOnly() != null && task.getForcePasteOnly() == 1,
                    task.getAllowPasteFallback() == null || task.getAllowPasteFallback() == 1,
                    pasteResumeRound,
                    ArticleSupportLevel.from(task.getSupportLevel())
            );
        }
    }

    public record PasteBlock(String errorCode, String message) {
    }

    private ArticleFetchPolicy() {
    }

    public static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * PASTE_ONLY / UNSUPPORTED 且无粘贴：零 HTTP，进 NEEDS_PASTE。
     */
    public static PasteBlock blockWithoutPaste(Snapshot s) {
        if (s.hasPaste()) {
            return null;
        }
        if (s.supportLevel() == ArticleSupportLevel.PASTE_ONLY) {
            return new PasteBlock(ArticleErrorCode.PLATFORM_PASTE_ONLY,
                    "该平台未开放自动抓取，请粘贴正文后继续");
        }
        if (s.supportLevel() == ArticleSupportLevel.UNSUPPORTED) {
            return new PasteBlock(ArticleErrorCode.PLATFORM_UNSUPPORTED,
                    "该平台未开放自动抓取，请粘贴正文后继续");
        }
        return null;
    }

    public static boolean skipFetch(Snapshot s) {
        if (s.forcePaste() && s.hasPaste()) {
            return true;
        }
        if (!s.hasUrl() && s.hasPaste()) {
            return true;
        }
        if (s.hasPaste() && s.pasteResumeRound()) {
            return true;
        }
        if (s.supportLevel() == ArticleSupportLevel.PASTE_ONLY && s.hasPaste()) {
            return true;
        }
        return s.supportLevel() == ArticleSupportLevel.UNSUPPORTED && s.hasPaste();
    }

    /**
     * 抓取/SSRF/正文为空时：已有粘贴则用粘贴；否则按 allowFallback。
     */
    public static OnFailure onSourceFailure(Snapshot s) {
        if (s.hasPaste()) {
            return OnFailure.USE_PASTE;
        }
        if (s.allowFallback()) {
            return OnFailure.NEEDS_PASTE;
        }
        return OnFailure.FAIL;
    }
}
