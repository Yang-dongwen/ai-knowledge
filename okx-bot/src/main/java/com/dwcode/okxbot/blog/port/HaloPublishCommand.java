package com.dwcode.okxbot.blog.port;

import java.util.List;

/**
 * 发往 Halo 的一篇文章。
 */
public record HaloPublishCommand(
        String title,
        String slug,
        String raw,
        String rawType,
        String existingPostName,
        List<String> categoryNames,
        List<String> tagNames,
        String cover
) {
    public HaloPublishCommand {
        categoryNames = categoryNames == null ? null : List.copyOf(categoryNames);
        tagNames = tagNames == null ? null : List.copyOf(tagNames);
    }

    public HaloPublishCommand(String title, String slug, String raw, String rawType, String existingPostName) {
        this(title, slug, raw, rawType, existingPostName, List.of(), List.of(), null);
    }

    public HaloPublishCommand(String title, String slug, String raw, String rawType, String existingPostName,
                              List<String> categoryNames, List<String> tagNames) {
        this(title, slug, raw, rawType, existingPostName, categoryNames, tagNames, null);
    }
}
