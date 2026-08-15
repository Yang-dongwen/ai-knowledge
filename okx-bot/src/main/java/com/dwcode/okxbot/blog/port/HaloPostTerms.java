package com.dwcode.okxbot.blog.port;

import java.util.List;

/**
 * 已有文章上的分类/标签（展示名）。
 */
public record HaloPostTerms(List<String> categoryNames, List<String> tagNames) {

    public static HaloPostTerms empty() {
        return new HaloPostTerms(List.of(), List.of());
    }
}
