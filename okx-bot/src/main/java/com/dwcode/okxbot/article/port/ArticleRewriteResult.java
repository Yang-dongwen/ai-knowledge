package com.dwcode.okxbot.article.port;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ArticleRewriteResult {
    private String rawJson;
    private int variantCount;
}
