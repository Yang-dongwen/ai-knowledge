package com.dwcode.okxbot.article.dto;

import lombok.Data;

import java.util.List;

/**
 * 创建文章提取任务的可选参数。
 */
@Data
public class ArticleCreateOptions {
    private String language = "zh";
    private String llmProvider;
    private String llmModel;
    private Boolean extractMindMap = false;
    private Boolean generateRewrite = true;
    private List<String> rewriteVariants;
    private Boolean allowPasteFallback = true;
    private Boolean forcePasteOnly = false;
}
