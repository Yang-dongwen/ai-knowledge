package com.dwcode.okxbot.article.port;

/**
 * 从抓取结果抽取主正文。
 */
public interface ArticleExtractPort {

    MainTextDocument extract(ArticleFetchResult raw);

    /**
     * 从用户粘贴文本清洗为 MainTextDocument。
     */
    MainTextDocument fromPaste(String pasteText, String titleHint);
}
