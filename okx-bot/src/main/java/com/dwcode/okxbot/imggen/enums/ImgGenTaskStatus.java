package com.dwcode.okxbot.imggen.enums;

/**
 * AI 文生图任务状态。
 */
public enum ImgGenTaskStatus {
    PENDING,
    PROMPT_ENHANCING,
    GENERATING,
    SUCCESS,
    FAILED,
    CANCELLED,
    PAUSED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED || this == PAUSED;
    }

    public boolean isRunning() {
        return this == PROMPT_ENHANCING || this == GENERATING;
    }

    public static ImgGenTaskStatus from(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return ImgGenTaskStatus.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
