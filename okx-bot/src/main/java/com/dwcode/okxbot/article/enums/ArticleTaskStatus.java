package com.dwcode.okxbot.article.enums;

/**
 * 文章/新闻提取任务状态。
 *
 * <p>{@link #NEEDS_PASTE} 为等待用户的软终态：{@code isTerminal=true}、
 * {@code isWaitingUser=true}、不占调度 running 槽。
 */
public enum ArticleTaskStatus {
    PENDING,
    RESOLVING,
    FETCHING,
    EXTRACTING,
    LLM_CORE,
    LLM_REWRITE,
    NEEDS_PASTE,
    SUCCESS,
    FAILED,
    CANCELLED,
    PAUSED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED
                || this == PAUSED || this == NEEDS_PASTE;
    }

    public boolean isRunning() {
        return this == RESOLVING || this == FETCHING || this == EXTRACTING
                || this == LLM_CORE || this == LLM_REWRITE;
    }

    public boolean isWaitingUser() {
        return this == NEEDS_PASTE;
    }

    public static ArticleTaskStatus from(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return ArticleTaskStatus.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
