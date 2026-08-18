package com.dwcode.okxbot.horizon.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.horizon.config.HorizonProperties;
import com.dwcode.okxbot.horizon.dto.HorizonDigestBrief;
import com.dwcode.okxbot.horizon.dto.HorizonDigestRequest;
import com.dwcode.okxbot.horizon.dto.HorizonDigestResponse;
import com.dwcode.okxbot.horizon.dto.HorizonDigestView;
import com.dwcode.okxbot.horizon.entity.HorizonDigestEntity;
import com.dwcode.okxbot.horizon.mapper.HorizonDigestMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 公共时讯：库表 horizon_digest（无 user_id）。本地 summaries 仅作本机兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HorizonIngestService {

    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final int MAX_MARKDOWN = 2_097_152;

    private final HorizonProperties properties;
    private final HorizonDigestMapper digestMapper;
    private final HorizonSummaryFiles summaryFiles;

    public HorizonDigestView latest(String lang) {
        return latest(lang, null);
    }

    public boolean hasToday(String lang) {
        String resolved = resolveLang(lang);
        String day = shanghaiToday();
        HorizonDigestEntity row = digestMapper.selectById(day);
        return row != null && (resolved.equals(row.getLang()) || row.getLang() == null);
    }

    /** 产品「当天」= 北京日历日。不再把 UTC 昨天算进今天，否则会挡住新稿入库/发博。 */
    static List<String> todayDates() {
        return List.of(shanghaiToday());
    }

    static String shanghaiToday() {
        return LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
    }

    public HorizonDigestView latest(String lang, String date) {
        String resolved = resolveLang(lang);
        if (!StringUtils.hasText(date)) {
            for (String day : todayDates()) {
                HorizonDigestView today = latest(resolved, day);
                if (today != null) {
                    return today;
                }
            }
            return null;
        }
        String day = resolveDate(date);
        HorizonDigestEntity row = digestMapper.selectById(day);
        if (row != null && resolved.equals(row.getLang())) {
            return toView(row);
        }
        if (row != null) {
            return toView(row);
        }
        return summaryFiles.latest(resolved, day).orElse(null);
    }

    public List<HorizonDigestBrief> recent(String lang, int limit) {
        String resolved = resolveLang(lang);
        int size = Math.min(Math.max(limit, 1), 30);
        List<HorizonDigestEntity> rows = digestMapper.selectList(
                new LambdaQueryWrapper<HorizonDigestEntity>()
                        .eq(HorizonDigestEntity::getLang, resolved)
                        .orderByDesc(HorizonDigestEntity::getDigestDate)
                        .last("LIMIT " + size));
        if (!rows.isEmpty()) {
            return rows.stream().map(this::toBrief).toList();
        }
        return summaryFiles.recent(resolved, size);
    }

    public HorizonDigestResponse ingest(String providedToken, HorizonDigestRequest request) {
        if (!properties.webhookOpen()) {
            throw new BusinessException(503, "Horizon 入库未配置 token");
        }
        if (!tokenMatches(providedToken)) {
            throw new BusinessException(401, "Horizon token 无效");
        }
        return save(request == null ? new HorizonDigestRequest() : request);
    }

    public HorizonDigestResponse save(HorizonDigestRequest req) {
        String lang = resolveLang(req.getLang());
        String date = resolveDate(req.getDate());
        String markdown = resolveMarkdown(req);
        String title = canonicalTitle(date, lang);
        HorizonDigestEntity existing = digestMapper.selectById(date);
        boolean created = existing == null;
        HorizonDigestEntity row = existing == null ? new HorizonDigestEntity() : existing;
        row.setDigestDate(date);
        row.setLang(lang);
        row.setTitle(title);
        row.setMarkdown(markdown);
        row.setSnippet(snippet(markdown));
        row.setUpdatedAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
        if (created) {
            digestMapper.insert(row);
        } else {
            digestMapper.updateById(row);
        }
        log.info("horizon digest saved date={} created={}", date, created);
        return HorizonDigestResponse.builder()
                .title(title)
                .created(created)
                .published(StringUtils.hasText(row.getHaloPermalink()))
                .haloPermalink(row.getHaloPermalink())
                .build();
    }

    public void rememberHalo(String date, String postName, String permalink) {
        HorizonDigestEntity row = digestMapper.selectById(date);
        if (row == null) {
            return;
        }
        row.setHaloPostName(postName);
        row.setHaloPermalink(permalink);
        digestMapper.updateById(row);
    }

    public String haloPostName(String date) {
        HorizonDigestEntity row = digestMapper.selectById(date);
        return row == null ? null : row.getHaloPostName();
    }

    static String titlePrefix(String lang) {
        return "en".equals(lang) ? "Horizon Daily " : "Horizon 每日速递 ";
    }

    static String canonicalTitle(String date, String lang) {
        return titlePrefix(lang) + date;
    }

    private HorizonDigestView toView(HorizonDigestEntity e) {
        return HorizonDigestView.builder()
                .title(e.getTitle())
                .date(e.getDigestDate())
                .lang(e.getLang())
                .markdown(e.getMarkdown())
                .snippet(e.getSnippet())
                .haloPermalink(e.getHaloPermalink())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private HorizonDigestBrief toBrief(HorizonDigestEntity e) {
        return HorizonDigestBrief.builder()
                .title(e.getTitle())
                .date(e.getDigestDate())
                .lang(e.getLang())
                .snippet(e.getSnippet())
                .haloPermalink(e.getHaloPermalink())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private String resolveMarkdown(HorizonDigestRequest req) {
        String raw = StringUtils.hasText(req.getMarkdown()) ? req.getMarkdown() : req.getSummary();
        if (!StringUtils.hasText(raw)) {
            throw new BusinessException(400, "markdown 不能为空");
        }
        String md = raw.strip();
        if (md.length() > MAX_MARKDOWN) {
            throw new BusinessException(400, "日报超过正文上限");
        }
        return decodeQuoteEntities(md);
    }

    /** Horizon 曾把 ' 写成 &#x27;，入库时还原，避免页面露出实体。 */
    static String decodeQuoteEntities(String markdown) {
        return markdown
                .replace("&\\#x27;", "'")
                .replace("&\\#X27;", "'")
                .replace("&\\#39;", "'")
                .replace("&#x27;", "'")
                .replace("&#X27;", "'")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&quot;", "\"");
    }

    private String resolveDate(String raw) {
        if (StringUtils.hasText(raw)) {
            String d = raw.trim();
            if (!ISO_DATE.matcher(d).matches()) {
                throw new BusinessException(400, "date 须为 YYYY-MM-DD");
            }
            return d;
        }
        return shanghaiToday();
    }

    private String resolveLang(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "zh";
        }
        String lang = raw.trim().toLowerCase(Locale.ROOT);
        return lang.startsWith("en") ? "en" : "zh";
    }

    private static String snippet(String markdown) {
        String plain = markdown.replaceAll("[#>*`\\[\\]()]", " ").replaceAll("\\s+", " ").trim();
        return plain.length() <= 160 ? plain : plain.substring(0, 160);
    }

    boolean tokenMatches(String provided) {
        String expected = properties.getToken();
        if (!StringUtils.hasText(expected) || !StringUtils.hasText(provided)) {
            return false;
        }
        byte[] a = sha256(expected.trim());
        byte[] b = sha256(provided.trim());
        return MessageDigest.isEqual(a, b);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
