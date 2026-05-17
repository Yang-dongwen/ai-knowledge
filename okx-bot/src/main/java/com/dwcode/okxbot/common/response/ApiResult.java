package com.dwcode.okxbot.common.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统一响应结构。
 */
@Data
public class ApiResult<T> {

    private int code;
    private String message;
    private T data;
    private boolean success;
    private LocalDateTime timestamp;

    private ApiResult() {
        this.timestamp = LocalDateTime.now();
    }

    public static <T> ApiResult<T> ok(T data) {
        ApiResult<T> result = new ApiResult<>();
        result.setCode(0);
        result.setMessage("success");
        result.setData(data);
        result.setSuccess(true);
        return result;
    }

    public static <T> ApiResult<T> ok() {
        return ok(null);
    }

    public static <T> ApiResult<T> fail(int code, String message) {
        ApiResult<T> result = new ApiResult<>();
        result.setCode(code);
        result.setMessage(message);
        result.setData(null);
        result.setSuccess(false);
        return result;
    }

    public static <T> ApiResult<T> fail(String message) {
        return fail(500, message);
    }
}
