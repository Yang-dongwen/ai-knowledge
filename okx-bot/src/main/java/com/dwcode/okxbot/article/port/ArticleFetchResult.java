package com.dwcode.okxbot.article.port;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ArticleFetchResult {
    private boolean success;
    private String errorCode;
    private String errorMessage;
    private String finalUrl;
    private String contentType;
    private String rawHtml;
    private String rawText;
    private String titleHint;
    private String authorHint;
    private int httpStatus;
    private long latencyMs;
    private List<String> imageUrls;
    private List<Map<String, Object>> images;

    public static ArticleFetchResult fail(String code, String message) {
        return ArticleFetchResult.builder()
                .success(false)
                .errorCode(code)
                .errorMessage(message)
                .build();
    }
}
