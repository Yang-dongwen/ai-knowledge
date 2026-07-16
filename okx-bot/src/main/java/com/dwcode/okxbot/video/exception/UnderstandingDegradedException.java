package com.dwcode.okxbot.video.exception;

/**
 * 多模态理解失败但允许降级为纯音频总结。
 */
public class UnderstandingDegradedException extends Exception {

    private final String reason;

    public UnderstandingDegradedException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public UnderstandingDegradedException(String reason, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
    }

    public String getReason() {
        return reason != null ? reason : getMessage();
    }
}
