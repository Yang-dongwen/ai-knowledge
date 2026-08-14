package com.dwcode.okxbot.blog.port;

/**
 * 发往 Halo 的一篇文章。
 */
public record HaloPublishCommand(
        String title,
        String slug,
        String raw,
        String rawType,
        String existingPostName
) {
}
