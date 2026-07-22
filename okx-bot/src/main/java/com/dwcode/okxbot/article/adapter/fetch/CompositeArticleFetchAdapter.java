package com.dwcode.okxbot.article.adapter.fetch;

import com.dwcode.okxbot.article.config.ArticleProperties;
import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import com.dwcode.okxbot.article.enums.ArticleSupportLevel;
import com.dwcode.okxbot.article.port.ArticleFetchCommand;
import com.dwcode.okxbot.article.port.ArticleFetchPort;
import com.dwcode.okxbot.article.port.ArticleFetchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 按 platform 路由抓取 Adapter；禁止 PASTE_ONLY/UNSUPPORTED 进入（Pipeline 层已拦截）。
 * <p>不注入 {@code List<ArticleFetchPort>}，避免 Composite 自引用循环依赖；
 * 显式注入专用 Adapter + {@link GenericHtmlFetchAdapter}，避免 List 自注入循环依赖。
 */
@Slf4j
@Component
@Primary
public class CompositeArticleFetchAdapter implements ArticleFetchPort {

    private final List<ArticleFetchPort> adapters;
    private final ArticleProperties properties;

    public CompositeArticleFetchAdapter(GenericHtmlFetchAdapter genericHtmlFetchAdapter,
                                        ToutiaoFetchAdapter toutiaoFetchAdapter,
                                        ArticleProperties properties) {
        this.properties = properties;
        List<ArticleFetchPort> list = new ArrayList<>();
        // 专用适配器在前，generic 回落在后
        list.add(toutiaoFetchAdapter);
        list.add(genericHtmlFetchAdapter);
        this.adapters = List.copyOf(list);
    }

    @Override
    public boolean supports(String platform) {
        return true;
    }

    @Override
    public ArticleFetchResult fetch(ArticleFetchCommand cmd) {
        String platform = cmd.getPlatform() != null ? cmd.getPlatform() : "generic";
        ArticleSupportLevel sl = ArticleSupportLevel.from(cmd.getSupportLevel());
        if (sl == ArticleSupportLevel.PASTE_ONLY || sl == ArticleSupportLevel.UNSUPPORTED) {
            return ArticleFetchResult.fail(
                    sl == ArticleSupportLevel.PASTE_ONLY
                            ? ArticleErrorCode.PLATFORM_PASTE_ONLY
                            : ArticleErrorCode.PLATFORM_UNSUPPORTED,
                    "该平台未开放自动抓取");
        }

        List<String> enabled = properties.getFetch().getEnabledAdapters();
        boolean genericEnabled = enabled == null || enabled.isEmpty()
                || enabled.stream().anyMatch(a -> a != null && a.equalsIgnoreCase("generic"));

        // 1) 专用 Adapter（非 generic fallback）
        for (ArticleFetchPort port : adapters) {
            if (port.isGenericFallback()) {
                continue;
            }
            if (port.supports(platform)) {
                String name = port.getClass().getSimpleName().toLowerCase(Locale.ROOT);
                if (enabled != null && !enabled.isEmpty()
                        && enabled.stream().noneMatch(a -> a != null
                        && (name.contains(a.toLowerCase(Locale.ROOT))
                        || a.equalsIgnoreCase(platform)))) {
                    log.info("Adapter {} 未在 enabled-adapters 中，跳过 platform={}",
                            port.getClass().getSimpleName(), platform);
                    continue;
                }
                log.info("Composite 选用专用 Adapter {} for platform={}",
                        port.getClass().getSimpleName(), platform);
                return port.fetch(cmd);
            }
        }

        // 2) Generic 回落
        if (!genericEnabled) {
            return ArticleFetchResult.fail(ArticleErrorCode.PLATFORM_UNSUPPORTED,
                    "generic 抓取未启用且无专用 Adapter: " + platform);
        }
        for (ArticleFetchPort port : adapters) {
            if (port.isGenericFallback()) {
                log.info("Composite 回落 GenericHtml for platform={}", platform);
                return port.fetch(cmd);
            }
        }
        return ArticleFetchResult.fail(ArticleErrorCode.PIPELINE_ERROR, "未注册任何 Fetch Adapter");
    }
}
