package com.dwcode.okxbot.common.exception;

import com.dwcode.okxbot.common.response.ApiResult;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResult<Void>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        int code = e.getCode();
        HttpStatus status = HttpStatus.resolve(code);
        if (status == null) {
            status = code >= 400 && code < 600 ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        // 业务码 4xx 映射到同号 HTTP 状态（如 404/416），其余 4xx 用 400
        if (code >= 400 && code < 500 && status.is5xxServerError()) {
            status = HttpStatus.BAD_REQUEST;
        }
        if (code > 0 && code < 400) {
            status = HttpStatus.OK;
        }
        return ResponseEntity.status(status).body(ApiResult.fail(code, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", message);
        return ApiResult.fail(400, message);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult<Void> handleBindException(BindException e) {
        String message = e.getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数绑定失败");
        log.warn("参数绑定失败: {}", message);
        return ApiResult.fail(400, message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult<Void> handleConstraintViolationException(ConstraintViolationException e) {
        log.warn("约束校验失败: {}", e.getMessage());
        return ApiResult.fail(400, e.getMessage());
    }

    /**
     * 常见于 PowerShell Invoke-RestMethod 未指定 -ContentType application/json，
     * 默认发出 text/plain;charset=UTF-8。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public ApiResult<Void> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        MediaType ct = e.getContentType();
        log.warn("不支持的 Content-Type: {}", ct);
        return ApiResult.fail(415,
                "Content-Type 不支持: " + ct
                        + "。JSON 接口请加请求头 Content-Type: application/json"
                        + "（PowerShell: -ContentType 'application/json'）");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResult<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return ApiResult.fail(500, "系统内部错误");
    }
}
