package com.dwcode.okxbot.video.enums;

/**
 * 视频处理任务状态。
 */
public enum VideoTaskStatus {
    /** 排队等待调度 */
    PENDING,
    /** 下载中 */
    DOWNLOADING,
    /** 转录中 */
    TRANSCRIBING,
    /** LLM 总结中 */
    SUMMARIZING,
    /** 成功 */
    SUCCESS,
    /** 失败 */
    FAILED,
    /** 用户暂停中断 */
    PAUSED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == PAUSED;
    }

    public boolean isRunning() {
        return this == DOWNLOADING || this == TRANSCRIBING || this == SUMMARIZING;
    }
}
