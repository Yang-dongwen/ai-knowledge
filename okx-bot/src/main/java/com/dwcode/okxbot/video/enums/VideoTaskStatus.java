package com.dwcode.okxbot.video.enums;

/**
 * 视频处理任务状态。
 */
public enum VideoTaskStatus {
    PENDING,
    DOWNLOADING,
    TRANSCRIBING,
    SUMMARIZING,
    SUCCESS,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }
}
