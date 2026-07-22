package com.dwcode.okxbot.article.enums;

/**
 * 文章提取错误码（落库 {@code error_code} / API）。
 */
public final class ArticleErrorCode {

    public static final String SSRF_BLOCKED = "SSRF_BLOCKED";
    public static final String INVALID_URL = "INVALID_URL";
    public static final String HTTP_403 = "HTTP_403";
    public static final String HTTP_404 = "HTTP_404";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String UNSUPPORTED_CONTENT_TYPE = "UNSUPPORTED_CONTENT_TYPE";
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    public static final String EMPTY_MAIN_TEXT = "EMPTY_MAIN_TEXT";
    public static final String PAYWALL_SUSPECTED = "PAYWALL_SUSPECTED";
    public static final String PLATFORM_PASTE_ONLY = "PLATFORM_PASTE_ONLY";
    public static final String PLATFORM_UNSUPPORTED = "PLATFORM_UNSUPPORTED";
    public static final String LLM_CORE_FAILED = "LLM_CORE_FAILED";
    public static final String LLM_REWRITE_FAILED = "LLM_REWRITE_FAILED";
    public static final String TEXT_TOO_LONG = "TEXT_TOO_LONG";
    public static final String CONCURRENT_LIMIT = "CONCURRENT_LIMIT";
    public static final String PIPELINE_ERROR = "PIPELINE_ERROR";

    private ArticleErrorCode() {
    }
}
