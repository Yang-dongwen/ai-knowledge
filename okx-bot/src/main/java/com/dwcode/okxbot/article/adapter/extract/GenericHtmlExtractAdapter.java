package com.dwcode.okxbot.article.adapter.extract;

import com.dwcode.okxbot.article.config.ArticleProperties;
import com.dwcode.okxbot.article.port.ArticleExtractPort;
import com.dwcode.okxbot.article.port.ArticleFetchResult;
import com.dwcode.okxbot.article.port.MainTextDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Jsoup 主内容抽取：去噪 + article/main 优先 + 文本密度启发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenericHtmlExtractAdapter implements ArticleExtractPort {

    private final ArticleProperties properties;

    @Override
    public MainTextDocument extract(ArticleFetchResult raw) {
        if (raw == null || !raw.isSuccess()) {
            return MainTextDocument.builder()
                    .unusable(true)
                    .unusableReason(raw != null ? raw.getErrorMessage() : "null result")
                    .qualityScore(0)
                    .source("html")
                    .build();
        }
        if (raw.getRawText() != null && (raw.getRawHtml() == null || raw.getRawHtml().isBlank())) {
            return fromPlain(raw.getRawText(), raw.getTitleHint());
        }
        String html = raw.getRawHtml();
        if (html == null || html.isBlank()) {
            return MainTextDocument.builder()
                    .unusable(true)
                    .unusableReason("empty html")
                    .qualityScore(0)
                    .source("html")
                    .build();
        }
        try {
            Document doc = Jsoup.parse(html, raw.getFinalUrl() != null ? raw.getFinalUrl() : "");
            doc.select("script,style,noscript,svg,iframe,nav,footer,aside,header,form").remove();
            String title = firstNonBlank(
                    raw.getTitleHint(),
                    textOrNull(doc.selectFirst("meta[property=og:title]"), "content"),
                    doc.title(),
                    textOrNull(doc.selectFirst("h1"), null)
            );
            String author = firstNonBlank(
                    raw.getAuthorHint(),
                    textOrNull(doc.selectFirst("meta[name=author]"), "content"),
                    textOrNull(doc.selectFirst("[rel=author]"), null)
            );

            Element mainEl = pickMainElement(doc);
            String mainText = mainEl != null ? cleanText(mainEl) : cleanText(doc.body());
            return buildDoc(title, author, mainText, "html");
        } catch (Exception e) {
            log.warn("HTML 抽取失败: {}", e.getMessage());
            return MainTextDocument.builder()
                    .unusable(true)
                    .unusableReason(e.getMessage())
                    .qualityScore(0)
                    .source("html")
                    .build();
        }
    }

    @Override
    public MainTextDocument fromPaste(String pasteText, String titleHint) {
        if (pasteText == null || pasteText.isBlank()) {
            return MainTextDocument.builder()
                    .unusable(true)
                    .unusableReason("empty paste")
                    .qualityScore(0)
                    .source("paste")
                    .build();
        }
        String cleaned = pasteText.replace("\r\n", "\n").trim();
        // 若粘贴像 HTML，走 jsoup
        if (cleaned.length() > 20 && cleaned.toLowerCase(Locale.ROOT).contains("<html")) {
            ArticleFetchResult fake = ArticleFetchResult.builder()
                    .success(true)
                    .rawHtml(cleaned)
                    .titleHint(titleHint)
                    .build();
            MainTextDocument d = extract(fake);
            d.setSource("paste");
            return d;
        }
        return buildDoc(titleHint, null, cleaned, "paste");
    }

    private MainTextDocument fromPlain(String text, String titleHint) {
        return buildDoc(titleHint, null, text != null ? text.trim() : "", "plain");
    }

    private MainTextDocument buildDoc(String title, String author, String mainText, String source) {
        int maxChars = Math.max(1000, properties.getExtract().getMaxMainTextChars());
        boolean truncated = false;
        String text = mainText != null ? mainText.trim() : "";
        // 折叠过多空行
        text = text.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars);
            truncated = true;
        }
        double score = scoreQuality(text);
        double min = properties.getExtract().getMinQualityScore();
        boolean unusable = text.isBlank() || score < min;
        return MainTextDocument.builder()
                .title(title)
                .author(author)
                .mainText(text)
                .qualityScore(score)
                .truncated(truncated)
                .unusable(unusable)
                .unusableReason(unusable ? (text.isBlank() ? "empty main text" : "low quality score") : null)
                .source(source)
                .build();
    }

    private Element pickMainElement(Document doc) {
        Element byArticle = doc.selectFirst("article");
        if (byArticle != null && byArticle.text().length() > 80) {
            return byArticle;
        }
        Element byMain = doc.selectFirst("main, [role=main], #content, .content, .article, .post, .entry-content");
        if (byMain != null && byMain.text().length() > 80) {
            return byMain;
        }
        // 文本密度启发：在 p 较多的块中选字数最多者
        Elements candidates = doc.select("div, section, article");
        List<Element> scored = new ArrayList<>();
        for (Element el : candidates) {
            if (el.text().length() < 100) {
                continue;
            }
            scored.add(el);
        }
        return scored.stream()
                .max(Comparator.comparingInt(e -> densityScore(e)))
                .orElse(doc.body());
    }

    private static int densityScore(Element el) {
        String text = el.ownText() + " " + el.select("p").text();
        int textLen = text.replaceAll("\\s+", "").length();
        int links = el.select("a").size();
        int tags = el.getAllElements().size();
        return textLen - links * 20 - Math.max(0, tags - 50);
    }

    private static String cleanText(Element el) {
        if (el == null) {
            return "";
        }
        // 优先段落
        Elements ps = el.select("p");
        if (ps.size() >= 2) {
            StringBuilder sb = new StringBuilder();
            for (Element p : ps) {
                String t = p.text().trim();
                if (t.length() < 2) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(t);
            }
            if (sb.length() > 40) {
                return sb.toString();
            }
        }
        return el.text();
    }

    private static double scoreQuality(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int len = text.length();
        if (len < 40) {
            return 0.1;
        }
        if (len < 120) {
            return 0.3;
        }
        // 中文/字母比例
        int letters = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c) || Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                letters++;
            }
        }
        double ratio = (double) letters / len;
        double base = Math.min(1.0, 0.4 + len / 2000.0);
        return Math.min(1.0, base * (0.5 + 0.5 * ratio));
    }

    private static String textOrNull(Element el, String attr) {
        if (el == null) {
            return null;
        }
        if (attr != null) {
            String v = el.attr(attr);
            return v != null && !v.isBlank() ? v.trim() : null;
        }
        String t = el.text();
        return t != null && !t.isBlank() ? t.trim() : null;
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
