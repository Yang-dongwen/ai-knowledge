package com.dwcode.okxbot.article.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 文章任务分页列表。
 */
@Data
@Builder
public class ArticleTaskPageResponse {
    private List<ArticleTaskResponse> items;
    private long total;
    private int page;
    private int size;
}
