package com.dwcode.okxbot.article.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交文章/新闻提取任务。
 * <p>校验：{@code url} 与 {@code pasteText} 至少一个非空（业务层实现）。
 */
@Data
public class ArticleCreateRequest {

    @Size(max = 2048, message = "url 最长 2048 字符")
    private String url;

    @Size(max = 100000, message = "pasteText 最长 100000 字符")
    private String pasteText;

    @Valid
    private ArticleCreateOptions options;
}
