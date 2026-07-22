package com.dwcode.okxbot.article.port;

import lombok.Builder;
import lombok.Data;

/**
 * CORE 结构化结果；rawJson 供落库，parsed 供 REWRITE。
 */
@Data
@Builder
public class ArticleCoreResult {
    private String rawJson;
    private String title;
    private String summary;
    private boolean truncatedInput;
    private int mapLlmCalls;
}
