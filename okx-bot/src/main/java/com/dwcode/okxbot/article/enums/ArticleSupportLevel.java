package com.dwcode.okxbot.article.enums;

/**
 * 平台自动抓取支持等级（产品 badge + 服务端强制策略）。
 */
public enum ArticleSupportLevel {
    /** 默认可自动提取 */
    FULL,
    /** 可能失败，建议准备粘贴 */
    PARTIAL,
    /** 未开放抓取，须粘贴正文；服务端零 HTTP */
    PASTE_ONLY,
    /** 暂不支持抓取，须粘贴 */
    UNSUPPORTED;

    public static ArticleSupportLevel from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ArticleSupportLevel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
