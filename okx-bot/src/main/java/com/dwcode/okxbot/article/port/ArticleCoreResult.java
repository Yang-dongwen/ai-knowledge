package com.dwcode.okxbot.article.port;

import lombok.Builder;
import lombok.Data;

/**
 * CORE 结构化结果；rawJson 供落库与 REWRITE 输入。
 */
@Data
@Builder
public class ArticleCoreResult {
    private String rawJson;
    private String title;
    private String summary;
    private boolean truncatedInput;
}
