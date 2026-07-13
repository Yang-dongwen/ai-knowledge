package com.dwcode.okxbot.aigen.enums;

/**
 * AI 视频生成任务状态。
 */
public enum AigenTaskStatus {
    /** 排队 */
    PENDING,
    /** LLM 分镜规划中（Phase 0 为 mock） */
    PLANNING,
    /** 素材生成中（Phase 0 为 mock） */
    ASSET_GENERATING,
    /** Remotion 渲染中（Phase 0 为 mock） */
    RENDERING,
    /** 成功 */
    SUCCESS,
    /** 失败 */
    FAILED,
    /** 用户取消 */
    CANCELLED,
    /** 用户暂停（步骤边界中断） */
    PAUSED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED || this == PAUSED;
    }

    public boolean isRunning() {
        return this == PLANNING || this == ASSET_GENERATING || this == RENDERING;
    }

    public static AigenTaskStatus from(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return AigenTaskStatus.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
