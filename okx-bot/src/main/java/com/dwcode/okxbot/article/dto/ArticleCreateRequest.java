package com.dwcode.okxbot.article.dto;

import lombok.Data;

/**
 * 提交文章/新闻提取任务。
 * <p>校验：{@code url} 与 {@code pasteText} 至少一个非空（业务层 PR-3+ 实现）。
 */
@Data
public class ArticleCreateRequest {
    private String url;
    private String pasteText;
    private ArticleCreateOptions options;
}
