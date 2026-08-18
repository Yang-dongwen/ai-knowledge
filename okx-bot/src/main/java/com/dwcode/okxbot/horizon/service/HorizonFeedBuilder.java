package com.dwcode.okxbot.horizon.service;

import com.dwcode.okxbot.horizon.dto.HorizonDigestView;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把日报 Markdown 拆成 RSS item；供 Halo Cosolar「资讯」页订阅。
 */
final class HorizonFeedBuilder {

    private static final Pattern ITEM = Pattern.compile(
            "(?m)^###\\s+\\[(?<title>[^\\]]+)]\\((?<url>https?://[^)\\s]+)\\)");
    private static final DateTimeFormatter RFC822 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);

    record Item(String title, String url, String date, String description) {
    }

    private HorizonFeedBuilder() {
    }

    static List<Item> items(List<HorizonDigestView> digests, String newsUrl) {
        List<Item> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (digests == null) {
            return out;
        }
        for (HorizonDigestView digest : digests) {
            if (digest == null) {
                continue;
            }
            String md = HorizonIngestService.decodeQuoteEntities(
                    digest.getMarkdown() == null ? "" : digest.getMarkdown());
            Matcher m = ITEM.matcher(md);
            boolean any = false;
            while (m.find()) {
                String title = m.group("title").trim();
                String url = m.group("url").trim();
                if (title.isEmpty() || !seen.add(url)) {
                    continue;
                }
                any = true;
                out.add(new Item(title, url, digest.getDate(), snippetAround(md, m.start())));
            }
            if (!any && digest.getTitle() != null && !digest.getTitle().isBlank()) {
                String fallbackUrl = newsUrl;
                if (seen.add(fallbackUrl + "#" + digest.getDate())) {
                    out.add(new Item(digest.getTitle(), newsUrl, digest.getDate(), digest.getSnippet()));
                }
            }
        }
        return out;
    }

    static String rss(String channelTitle, String channelLink, String channelDesc, List<Item> items) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<rss version=\"2.0\">\n<channel>\n");
        sb.append("<title>").append(xml(channelTitle)).append("</title>\n");
        sb.append("<link>").append(xml(channelLink)).append("</link>\n");
        sb.append("<description>").append(xml(channelDesc)).append("</description>\n");
        sb.append("<language>zh-cn</language>\n");
        if (items != null) {
            for (Item item : items) {
                sb.append("<item>\n");
                sb.append("<title>").append(xml(item.title())).append("</title>\n");
                sb.append("<link>").append(xml(item.url())).append("</link>\n");
                sb.append("<guid isPermaLink=\"true\">").append(xml(item.url())).append("</guid>\n");
                sb.append("<pubDate>").append(xml(pubDate(item.date()))).append("</pubDate>\n");
                if (item.description() != null && !item.description().isBlank()) {
                    sb.append("<description><![CDATA[")
                            .append(item.description().replace("]]>", "]]&gt;"))
                            .append("]]></description>\n");
                }
                sb.append("</item>\n");
            }
        }
        sb.append("</channel>\n</rss>\n");
        return sb.toString();
    }

    private static String snippetAround(String md, int start) {
        int from = start;
        int to = Math.min(md.length(), start + 400);
        String chunk = md.substring(from, to).replace('\n', ' ').trim();
        return chunk.length() <= 220 ? chunk : chunk.substring(0, 220);
    }

    private static String pubDate(String isoDate) {
        try {
            if (isoDate != null && !isoDate.isBlank()) {
                ZonedDateTime z = LocalDate.parse(isoDate.trim())
                        .atStartOfDay(ZoneId.of("Asia/Shanghai"));
                return RFC822.format(z);
            }
        } catch (Exception ignored) {
            // fall through
        }
        return RFC822.format(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")));
    }

    private static String xml(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
