package com.dwcode.okxbot.article.port;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ArticleRewriteCommand {
    private String language;
    private String llmProvider;
    private String llmModel;
    private List<String> variants;
    private String titleHint;
}
