package com.dwcode.okxbot.kb.pdf;

import org.springframework.util.StringUtils;

/**
 * 把笔记正文包进 Typora Vue 打印页（与简历 PDF 同一套 CSS / 字体）。
 */
public final class KbVuePdfDocument {

    static final String[] FONT_FILES = {
            "source-sans-pro-400.woff2",
            "source-sans-pro-600.woff2",
            "roboto-mono-400.woff2",
            "noto-sans-sc-300.woff2",
            "noto-sans-sc-400.woff2",
            "noto-sans-sc-600.woff2"
    };

    private KbVuePdfDocument() {
    }

    public static String wrap(String title, String bodyHtml, String css) {
        String safeTitle = escape(StringUtils.hasText(title) ? title : "未命名笔记");
        String body = stripScripts(bodyHtml == null ? "" : bodyHtml);
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="utf-8"/>
                <title>%s</title>
                <style>
                %s
                %s
                </style>
                </head>
                <body>
                <article id="write">%s</article>
                </body>
                </html>
                """.formatted(safeTitle, fontFaceCss(), css == null ? "" : css, body);
    }

    static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    static String stripScripts(String html) {
        return html.replaceAll("(?is)<script\\b[^>]*>.*?</script>", "")
                .replaceAll("(?i)<script\\b[^>]*>", "");
    }

    static String fontFaceCss() {
        return """
                @font-face {
                  font-family: 'Source Sans Pro';
                  font-style: normal;
                  font-weight: 400;
                  src: url('source-sans-pro-400.woff2') format('woff2');
                }
                @font-face {
                  font-family: 'Source Sans Pro';
                  font-style: normal;
                  font-weight: 600;
                  src: url('source-sans-pro-600.woff2') format('woff2');
                }
                @font-face {
                  font-family: 'Roboto Mono';
                  font-style: normal;
                  font-weight: 400;
                  src: url('roboto-mono-400.woff2') format('woff2');
                }
                @font-face {
                  font-family: 'Noto Sans SC';
                  font-style: normal;
                  font-weight: 300;
                  src: url('noto-sans-sc-300.woff2') format('woff2');
                }
                @font-face {
                  font-family: 'Noto Sans SC';
                  font-style: normal;
                  font-weight: 400;
                  src: url('noto-sans-sc-400.woff2') format('woff2');
                }
                @font-face {
                  font-family: 'Noto Sans SC';
                  font-style: normal;
                  font-weight: 600;
                  src: url('noto-sans-sc-600.woff2') format('woff2');
                }
                """;
    }
}
