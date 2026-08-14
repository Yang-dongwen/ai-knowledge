package com.dwcode.okxbot.blog.port;

/**
 * Halo 发文结果。
 */
public record HaloPublishResult(
        String postName,
        String publicUrl,
        String permalink
) {
}
