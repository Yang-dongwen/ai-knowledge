package com.dwcode.okxbot.article.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ArticlePlatformDetectResponse {
    private String url;
    private String host;
    private String platform;
    private String supportLevel;
    private String message;
    private Boolean skipFetch;
}
