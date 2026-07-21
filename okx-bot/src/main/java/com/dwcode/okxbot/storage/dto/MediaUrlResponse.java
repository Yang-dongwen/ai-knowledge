package com.dwcode.okxbot.storage.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 媒体直链结果：R2 预签名或回退代理路径。
 */
@Data
@Builder
public class MediaUrlResponse {
    /**
     * 播放/下载 URL。
     * <ul>
     *   <li>{@code mode=presign}：完整 HTTPS，直连 R2（含签名查询串）</li>
     *   <li>{@code mode=proxy}：同源相对路径（如 /api/v1/video/tasks/1/video），前端需带 JWT 或 access_token</li>
     * </ul>
     */
    private String url;
    /** presign | proxy */
    private String mode;
    /** 预签名过期时刻（epoch ms）；proxy 时为 0 */
    private long expiresAtMs;
    /** 建议 TTL 秒（前端刷新参考） */
    private int ttlSeconds;
    /** object key（调试用，可空） */
    private String objectKey;
    /** 回退代理路径（presign 时也返回，前端失败可降级） */
    private String proxyPath;
}
