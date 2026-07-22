package com.dwcode.okxbot.article.adapter.fetch;

import com.dwcode.okxbot.article.config.ArticleProperties;
import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import com.dwcode.okxbot.article.enums.ArticleSupportLevel;
import com.dwcode.okxbot.article.port.ArticleFetchCommand;
import com.dwcode.okxbot.article.port.ArticleFetchResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompositeArticleFetchAdapterTest {

    private CompositeArticleFetchAdapter newComposite(ArticleProperties props,
                                                      GenericHtmlFetchAdapter generic,
                                                      ToutiaoFetchAdapter toutiao) {
        return new CompositeArticleFetchAdapter(generic, toutiao, props);
    }

    @Test
    void pasteOnlyBlockedAtComposite() {
        ArticleProperties props = new ArticleProperties();
        GenericHtmlFetchAdapter generic = mock(GenericHtmlFetchAdapter.class);
        ToutiaoFetchAdapter toutiao = mock(ToutiaoFetchAdapter.class);
        when(generic.isGenericFallback()).thenReturn(true);
        when(generic.fetch(any())).thenReturn(
                ArticleFetchResult.builder().success(true).rawHtml("<html/>").build());

        CompositeArticleFetchAdapter composite = newComposite(props, generic, toutiao);

        ArticleFetchResult r = composite.fetch(ArticleFetchCommand.builder()
                .url("https://www.xiaohongshu.com/a")
                .platform("xiaohongshu")
                .supportLevel(ArticleSupportLevel.PASTE_ONLY.name())
                .build());
        assertFalse(r.isSuccess());
        assertEquals(ArticleErrorCode.PLATFORM_PASTE_ONLY, r.getErrorCode());
    }

    @Test
    void fullRoutesToGeneric() {
        ArticleProperties props = new ArticleProperties();
        GenericHtmlFetchAdapter generic = mock(GenericHtmlFetchAdapter.class);
        ToutiaoFetchAdapter toutiao = mock(ToutiaoFetchAdapter.class);
        when(generic.isGenericFallback()).thenReturn(true);
        when(generic.supports(any())).thenReturn(true);
        when(generic.fetch(any())).thenReturn(
                ArticleFetchResult.builder().success(true).rawHtml("ok")
                        .finalUrl("https://example.com/n").build());
        when(toutiao.supports(any())).thenReturn(false);

        CompositeArticleFetchAdapter composite = newComposite(props, generic, toutiao);

        ArticleFetchResult r = composite.fetch(ArticleFetchCommand.builder()
                .url("https://example.com/n")
                .platform("generic")
                .supportLevel(ArticleSupportLevel.FULL.name())
                .build());
        assertEquals(true, r.isSuccess());
        assertEquals("ok", r.getRawHtml());
    }

    @Test
    void toutiaoRoutesToDedicatedAdapter() {
        ArticleProperties props = new ArticleProperties();
        GenericHtmlFetchAdapter generic = mock(GenericHtmlFetchAdapter.class);
        ToutiaoFetchAdapter toutiao = mock(ToutiaoFetchAdapter.class);
        when(toutiao.isGenericFallback()).thenReturn(false);
        when(toutiao.supports("toutiao")).thenReturn(true);
        when(toutiao.fetch(any())).thenReturn(
                ArticleFetchResult.builder().success(true).rawHtml("<article>tt</article>")
                        .titleHint("头条文").build());
        when(generic.isGenericFallback()).thenReturn(true);

        CompositeArticleFetchAdapter composite = newComposite(props, generic, toutiao);

        ArticleFetchResult r = composite.fetch(ArticleFetchCommand.builder()
                .url("https://www.toutiao.com/article/123/")
                .platform("toutiao")
                .supportLevel(ArticleSupportLevel.PARTIAL.name())
                .build());
        assertEquals(true, r.isSuccess());
        assertEquals("头条文", r.getTitleHint());
    }
}
