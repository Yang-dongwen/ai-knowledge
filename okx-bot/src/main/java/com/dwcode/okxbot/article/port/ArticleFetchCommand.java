package com.dwcode.okxbot.article.port;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.util.Map;

@Data
@Builder
public class ArticleFetchCommand {
    private String taskId;
    private String url;
    private String platform;
    private String supportLevel;
    private String language;
    private int connectTimeoutMs;
    private int readTimeoutMs;
    private int maxBytes;
    private Map<String, String> headers;
    private Path workDir;
}
