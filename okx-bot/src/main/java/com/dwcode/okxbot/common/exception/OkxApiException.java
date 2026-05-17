package com.dwcode.okxbot.common.exception;

import lombok.Getter;

/**
 * OKX API 调用异常。
 */
@Getter
public class OkxApiException extends BusinessException {

    private final String errorCode;
    private final String rawResponse;

    public OkxApiException(String errorCode, String message, String rawResponse) {
        super(10002, message);
        this.errorCode = errorCode;
        this.rawResponse = rawResponse;
    }

    public OkxApiException(String message) {
        this(null, message, null);
    }
}
