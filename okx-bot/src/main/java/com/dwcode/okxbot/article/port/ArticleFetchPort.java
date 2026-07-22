package com.dwcode.okxbot.article.port;

/**
 * 平台抓取端口。多实现并存，由 {@code CompositeArticleFetchAdapter} 路由。
 */
public interface ArticleFetchPort {

    /**
     * 是否认领该 platform（不含 Composite 自身的回落逻辑）。
     */
    boolean supports(String platform);

    ArticleFetchResult fetch(ArticleFetchCommand cmd);

    /**
     * 是否作为 FULL/PARTIAL 的通用回落（GenericHtml）。
     */
    default boolean isGenericFallback() {
        return false;
    }
}
