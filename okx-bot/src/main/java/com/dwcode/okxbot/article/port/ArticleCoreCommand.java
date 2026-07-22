package com.dwcode.okxbot.article.port;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ArticleCoreCommand {
    private String language;
    private String llmProvider;
    private String llmModel;
    private boolean extractMindMap;
    private String titleHint;
    private String sourceUrl;
    private String platform;
}
