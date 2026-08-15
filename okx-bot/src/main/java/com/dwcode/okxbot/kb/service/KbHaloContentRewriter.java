package com.dwcode.okxbot.kb.service;

import com.dwcode.okxbot.kb.entity.KbFileEntity;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把知识库私有附件路径换成 Halo 公开地址，不改原笔记。
 */
final class KbHaloContentRewriter {

    static final Pattern FILE_REF = Pattern.compile(
            "(?:https?://[^\\s\"')\\\\]+)?/api/v1/kb/files/(\\d+)/content(?:\\?[^\\s\"')\\\\]*)?",
            Pattern.CASE_INSENSITIVE);
    static final Pattern MD_IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\(([^)\\s]+)");
    static final Pattern HTML_IMAGE = Pattern.compile(
            "(?i)<img\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']");

    private KbHaloContentRewriter() {
    }

    static Set<Long> collectFileIds(String content) {
        Set<Long> ids = new LinkedHashSet<>();
        if (!StringUtils.hasText(content)) {
            return ids;
        }
        Matcher m = FILE_REF.matcher(content);
        while (m.find()) {
            try {
                ids.add(Long.parseLong(m.group(1)));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return ids;
    }

    static String replaceFileUrls(String content, Map<Long, String> permalinks) {
        if (!StringUtils.hasText(content) || permalinks == null || permalinks.isEmpty()) {
            return content == null ? "" : content;
        }
        Matcher m = FILE_REF.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Long id;
            try {
                id = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            String url = permalinks.get(id);
            m.appendReplacement(sb, Matcher.quoteReplacement(url != null ? url : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String appendExtraAttachments(String content, String format, List<KbFileEntity> extras,
                                         Function<KbFileEntity, String> permalinkOf) {
        if (extras == null || extras.isEmpty()) {
            return content == null ? "" : content;
        }
        List<String[]> rows = new ArrayList<>();
        for (KbFileEntity e : extras) {
            String url = permalinkOf.apply(e);
            if (!StringUtils.hasText(url)) {
                continue;
            }
            String name = StringUtils.hasText(e.getOriginalName()) ? e.getOriginalName() : "附件";
            rows.add(new String[]{name, url});
        }
        if (rows.isEmpty()) {
            return content == null ? "" : content;
        }
        boolean html = !"markdown".equalsIgnoreCase(format);
        StringBuilder extra = new StringBuilder();
        if (html) {
            extra.append("\n<h2>附件</h2>\n<ul>\n");
            for (String[] row : rows) {
                extra.append("<li><a href=\"").append(escapeHtml(row[1])).append("\">")
                        .append(escapeHtml(row[0])).append("</a></li>\n");
            }
            extra.append("</ul>\n");
        } else {
            extra.append("\n\n## 附件\n\n");
            for (String[] row : rows) {
                extra.append("- [").append(row[0].replace("]", "")).append("](").append(row[1]).append(")\n");
            }
        }
        return (content == null ? "" : content) + extra;
    }

    /** 正文里第一张图的公开地址（markdown / HTML）。 */
    static String firstImageUrl(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        Matcher md = MD_IMAGE.matcher(content);
        if (md.find() && isPublicImageUrl(md.group(1))) {
            return md.group(1);
        }
        Matcher html = HTML_IMAGE.matcher(content);
        if (html.find() && isPublicImageUrl(html.group(1))) {
            return html.group(1);
        }
        return null;
    }

    /** 未插入正文时，用第一张已上传的图片附件。 */
    static String firstBoundImagePermalink(Iterable<KbFileEntity> files, Map<Long, String> permalinks) {
        if (files == null || permalinks == null || permalinks.isEmpty()) {
            return null;
        }
        for (KbFileEntity e : files) {
            if (e == null || e.getId() == null || !"image".equalsIgnoreCase(e.getKind())) {
                continue;
            }
            String url = permalinks.get(e.getId());
            if (StringUtils.hasText(url) && isPublicImageUrl(url)) {
                return url;
            }
        }
        return null;
    }

    static boolean isPublicImageUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        String u = url.trim();
        if (u.startsWith("data:") || u.startsWith("blob:")) {
            return false;
        }
        return !u.contains("/api/v1/kb/files/");
    }

    static Map<Long, KbFileEntity> indexById(List<KbFileEntity> files) {
        Map<Long, KbFileEntity> map = new LinkedHashMap<>();
        if (files == null) {
            return map;
        }
        for (KbFileEntity e : files) {
            if (e != null && e.getId() != null) {
                map.put(e.getId(), e);
            }
        }
        return map;
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
