package com.dwcode.okxbot.article.dto;

import lombok.Data;

@Data
public class ArticleRetryRequest {
    private String llmProvider;
    private String llmModel;
    /** true 时清空 paste_text */
    private Boolean clearPaste;
}
