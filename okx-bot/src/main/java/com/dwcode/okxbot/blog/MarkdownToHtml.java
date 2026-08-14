package com.dwcode.okxbot.blog;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.List;

/**
 * 将 Markdown 渲染为 HTML，供 Halo 文章 content 字段使用。
 * Halo 前台读的是 content（HTML）；仅写 raw 会导致要编辑一次才正常显示。
 */
public final class MarkdownToHtml {

    private static final List<Extension> EXTENSIONS = List.of(
            TablesExtension.create(),
            StrikethroughExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS).build();

    private MarkdownToHtml() {
    }

    public static String render(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        return RENDERER.render(PARSER.parse(markdown));
    }
}
