package com.dwcode.okxbot.horizon.service;

import com.dwcode.okxbot.horizon.dto.HorizonDigestBrief;
import com.dwcode.okxbot.horizon.dto.HorizonDigestView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读 Horizon 已写出的 Markdown。Webhook 没跑时本机也能看稿。
 */
@Slf4j
@Component
public class HorizonSummaryFiles {

    static final Pattern FILE = Pattern.compile("horizon-(\\d{4}-\\d{2}-\\d{2})-(zh|en)\\.md");

    /** 单测注入；线上为空，按相对路径探测 */
    private final Path overrideDir;

    public HorizonSummaryFiles() {
        this.overrideDir = null;
    }

    HorizonSummaryFiles(Path overrideDir) {
        this.overrideDir = overrideDir;
    }

    public Optional<HorizonDigestView> latest(String lang, String date) {
        Path dir = resolveDir();
        if (dir == null) {
            return Optional.empty();
        }
        String resolved = lang == null ? "zh" : lang.toLowerCase(Locale.ROOT);
        if (date != null && !date.isBlank()) {
            return read(dir, date.trim(), resolved);
        }
        return list(dir, resolved, 1).stream().findFirst()
                .flatMap(b -> read(dir, b.getDate(), resolved));
    }

    public List<HorizonDigestBrief> recent(String lang, int limit) {
        Path dir = resolveDir();
        if (dir == null) {
            return List.of();
        }
        return list(dir, lang == null ? "zh" : lang.toLowerCase(Locale.ROOT), limit);
    }

    Path resolveDir() {
        if (overrideDir != null && Files.isDirectory(overrideDir)) {
            return overrideDir;
        }
        String cwd = System.getProperty("user.dir");
        Path[] candidates = {
                Path.of("/app/data/horizon/summaries"),
                Path.of(cwd, "data", "horizon", "summaries"),
                Path.of(cwd, "..", "Horizon", "data", "summaries"),
                Path.of(cwd, "..", "..", "Horizon", "data", "summaries")
        };
        for (Path raw : candidates) {
            Path p = raw.toAbsolutePath().normalize();
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return null;
    }

    private List<HorizonDigestBrief> list(Path dir, String lang, int limit) {
        List<HorizonDigestBrief> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "horizon-*-*-*.md")) {
            for (Path file : stream) {
                Matcher m = FILE.matcher(file.getFileName().toString());
                if (!m.matches() || !lang.equals(m.group(2))) {
                    continue;
                }
                String date = m.group(1);
                out.add(HorizonDigestBrief.builder()
                        .title(HorizonIngestService.canonicalTitle(date, lang))
                        .date(date)
                        .lang(lang)
                        .snippet(snippet(file))
                        .build());
            }
        } catch (IOException e) {
            log.warn("读取 Horizon summaries 失败: {}", e.getMessage());
            return List.of();
        }
        out.sort(Comparator.comparing(HorizonDigestBrief::getDate, Comparator.nullsLast(String::compareTo)).reversed());
        if (out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    private Optional<HorizonDigestView> read(Path dir, String date, String lang) {
        Path file = dir.resolve("horizon-" + date + "-" + lang + ".md").normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String markdown = Files.readString(file, StandardCharsets.UTF_8);
            java.time.LocalDateTime updated = java.time.LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(file).toInstant(), java.time.ZoneId.systemDefault());
            return Optional.of(HorizonDigestView.builder()
                    .title(HorizonIngestService.canonicalTitle(date, lang))
                    .date(date)
                    .lang(lang)
                    .markdown(markdown)
                    .snippet(truncate(markdown))
                    .updatedAt(updated)
                    .build());
        } catch (IOException e) {
            log.warn("读取 {} 失败: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    private static String snippet(Path file) {
        try {
            return truncate(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "";
        }
    }

    private static String truncate(String markdown) {
        if (markdown == null) {
            return "";
        }
        String plain = markdown.replaceAll("[#>*`\\[\\]()]", " ").replaceAll("\\s+", " ").trim();
        return plain.length() <= 160 ? plain : plain.substring(0, 160);
    }
}
