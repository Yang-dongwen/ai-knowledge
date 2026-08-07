package com.dwcode.okxbot.article.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建文章提取任务的可选参数。
 */
@Data
public class ArticleCreateOptions {

    @Size(max = 16, message = "language 最长 16 字符")
    private String language = "zh";

    private String llmProvider;
    private String llmModel;
    private Boolean extractMindMap = false;
    private Boolean generateRewrite = true;

    /** 改写变体 id 列表（如 short_video_script）；空则用配置默认。 */
    @Size(max = 10, message = "rewriteVariants 最多 10 项")
    private List<@Size(max = 64, message = "rewriteVariant 最长 64 字符") String> rewriteVariants;

    private Boolean allowPasteFallback = true;
    private Boolean forcePasteOnly = false;
}
