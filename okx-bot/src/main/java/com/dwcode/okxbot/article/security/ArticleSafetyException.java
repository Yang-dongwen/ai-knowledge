package com.dwcode.okxbot.article.security;

import com.dwcode.okxbot.common.exception.BusinessException;
import lombok.Getter;

/**
 * URL 安全 / 抓取相关异常，携带稳定 {@link #errorCode}。
 */
@Getter
public class ArticleSafetyException extends BusinessException {

    private final String errorCode;

    public ArticleSafetyException(String errorCode, String message) {
        super(400, message);
        this.errorCode = errorCode != null ? errorCode : "PIPELINE_ERROR";
    }

    public ArticleSafetyException(int httpCode, String errorCode, String message) {
        super(httpCode, message);
        this.errorCode = errorCode != null ? errorCode : "PIPELINE_ERROR";
    }
}
