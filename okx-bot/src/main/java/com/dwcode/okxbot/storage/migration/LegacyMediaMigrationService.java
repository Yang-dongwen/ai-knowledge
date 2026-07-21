package com.dwcode.okxbot.storage.migration;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.entity.AigenTaskEntity;
import com.dwcode.okxbot.aigen.mapper.AigenTaskMapper;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.imggen.config.ImgGenProperties;
import com.dwcode.okxbot.imggen.entity.ImgGenTaskEntity;
import com.dwcode.okxbot.imggen.mapper.ImgGenTaskMapper;
import com.dwcode.okxbot.storage.LocalObjectStorage;
import com.dwcode.okxbot.storage.ObjectKeyBuilder;
import com.dwcode.okxbot.storage.ObjectMeta;
import com.dwcode.okxbot.storage.ObjectStoragePort;
import com.dwcode.okxbot.storage.config.StorageProperties;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.entity.VideoTaskEntity;
import com.dwcode.okxbot.video.mapper.VideoTaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 将历史本地 work-dir 产物迁移到 {@link ObjectStoragePort}（local 根或 R2），并回写 DB path 为 object key。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegacyMediaMigrationService {

    private final ObjectStoragePort objectStorage;
    private final ObjectKeyBuilder keyBuilder;
    private final StorageProperties storageProperties;
    private final VideoProperties videoProperties;
    private final AigenProperties aigenProperties;
    private final ImgGenProperties imgGenProperties;
    private final VideoTaskMapper videoTaskMapper;
    private final AigenTaskMapper aigenTaskMapper;
    private final ImgGenTaskMapper imgGenTaskMapper;
    private final ObjectMapper objectMapper;

    public MigrationReport migrate(MigrationOptions options) {
        if (options == null) {
            options = MigrationOptions.builder().dryRun(true).build();
        }
        long t0 = System.currentTimeMillis();
        MigrationReport report = MigrationReport.builder()
                .dryRun(options.isDryRun())
                .deleteLocal(options.isDeleteLocal())
                .storageProvider(objectStorage.providerId())
                .envPrefix(storageProperties.getEnvPrefix())
                .startedAtMs(t0)
                .modules(new ArrayList<>())
                .build();

        Set<String> modules = resolveModules(options.getModules());
        for (String mod : modules) {
            switch (mod) {
                case "video" -> report.getModules().add(migrateVideo(options));
                case "aigen" -> report.getModules().add(migrateAigen(options));
                case "imggen" -> report.getModules().add(migrateImggen(options));
                default -> log.warn("忽略未知 module: {}", mod);
            }
        }
        report.setFinishedAtMs(System.currentTimeMillis());
        log.info("迁移结束: dryRun={} provider={} modules={} costMs={}",
                options.isDryRun(), objectStorage.providerId(), modules,
                report.getFinishedAtMs() - t0);
        return report;
    }

    private Set<String> resolveModules(List<String> requested) {
        Set<String> all = new LinkedHashSet<>(List.of("video", "aigen", "imggen"));
        if (requested == null || requested.isEmpty()) {
            return all;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String m : requested) {
            if (m == null || m.isBlank()) {
                continue;
            }
            String n = m.trim().toLowerCase(Locale.ROOT);
            if (all.contains(n)) {
                out.add(n);
            }
        }
        if (out.isEmpty()) {
            throw new BusinessException(400, "modules 无效，可选 video/aigen/imggen");
        }
        return out;
    }

    // ---------- video ----------

    private MigrationReport.ModuleReport migrateVideo(MigrationOptions opt) {
        MigrationReport.ModuleReport mr = baseModule("video");
        Path root = Path.of(videoProperties.getWorkDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            mr.getSamples().add("work-dir 不存在: " + root);
            return mr;
        }
        List<Path> dirs = listTaskDirs(root, opt);
        mr.setScannedDirs(dirs.size());
        for (Path dir : dirs) {
            String taskId = dir.getFileName().toString();
            try {
                migrateOneVideo(dir, taskId, opt, mr);
            } catch (Exception e) {
                mr.setFilesFailed(mr.getFilesFailed() + 1);
                addError(mr, "video/" + taskId + ": " + e.getMessage());
            }
            if (hitLimit(opt, mr)) {
                break;
            }
        }
        return mr;
    }

    private void migrateOneVideo(Path dir, String taskId, MigrationOptions opt, MigrationReport.ModuleReport mr)
            throws IOException {
        VideoTaskEntity entity = findVideo(taskId);
        long userId = entity != null && entity.getUserId() != null ? entity.getUserId() : 0L;
        boolean updated = false;

        // 优先 browser
        Path browser = dir.resolve("video.browser.mp4");
        Path videoMp4 = dir.resolve("video.mp4");
        if (Files.isRegularFile(browser)) {
            String key = uploadIfNeeded(userId, "video", taskId, browser, "video.browser.mp4", opt, mr);
            if (key != null && entity != null && needsRewrite(entity.getVideoPath(), key)) {
                if (!opt.isDryRun()) {
                    entity.setVideoPath(key);
                    updated = true;
                }
                sample(mr, "video " + taskId + " videoPath → " + key);
            }
        } else if (Files.isRegularFile(videoMp4)) {
            String key = uploadIfNeeded(userId, "video", taskId, videoMp4, "video.mp4", opt, mr);
            if (key != null && entity != null && needsRewrite(entity.getVideoPath(), key)) {
                if (!opt.isDryRun()) {
                    entity.setVideoPath(key);
                    updated = true;
                }
                sample(mr, "video " + taskId + " videoPath → " + key);
            }
        }

        // 其它字段
        updated |= migratePathField(entity, userId, "video", taskId, entity != null ? entity.getAudioPath() : null,
                dir, "audio", opt, mr, (e, k) -> e.setAudioPath(k));
        updated |= migratePathField(entity, userId, "video", taskId,
                entity != null ? entity.getTranscriptionPath() : null,
                dir, "transcription.json", opt, mr, (e, k) -> e.setTranscriptionPath(k));
        updated |= migratePathField(entity, userId, "video", taskId,
                entity != null ? entity.getSummaryPath() : null,
                dir, "summary.json", opt, mr, (e, k) -> e.setSummaryPath(k));
        updated |= migratePathField(entity, userId, "video", taskId,
                entity != null ? entity.getVisualPath() : null,
                dir, "visual_understanding.json", opt, mr, (e, k) -> e.setVisualPath(k));

        // 扫目录剩余标准文件
        try (Stream<Path> list = Files.list(dir)) {
            for (Path f : list.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".mp4") || lower.endsWith(".mp3") || lower.endsWith(".m4a")
                        || lower.endsWith(".json") || lower.endsWith(".webm"))) {
                    continue;
                }
                uploadIfNeeded(userId, "video", taskId, f, name, opt, mr);
            }
        }

        if (entity != null && updated && !opt.isDryRun()) {
            entity.setUpdatedAt(LocalDateTime.now());
            videoTaskMapper.updateById(entity);
            mr.setTasksUpdated(mr.getTasksUpdated() + 1);
        }

        if (opt.isDeleteLocal() && !opt.isDryRun()) {
            mr.setLocalsDeleted(mr.getLocalsDeleted() + deleteDirIfAllUploaded(dir, userId, "video", taskId, mr));
        }
    }

    // ---------- aigen ----------

    private MigrationReport.ModuleReport migrateAigen(MigrationOptions opt) {
        MigrationReport.ModuleReport mr = baseModule("aigen");
        Path root = Path.of(aigenProperties.getWorkDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            mr.getSamples().add("work-dir 不存在: " + root);
            return mr;
        }
        List<Path> dirs = listTaskDirs(root, opt);
        mr.setScannedDirs(dirs.size());
        for (Path dir : dirs) {
            String taskId = dir.getFileName().toString();
            try {
                migrateOneAigen(dir, taskId, opt, mr);
            } catch (Exception e) {
                mr.setFilesFailed(mr.getFilesFailed() + 1);
                addError(mr, "aigen/" + taskId + ": " + e.getMessage());
            }
            if (hitLimit(opt, mr)) {
                break;
            }
        }
        return mr;
    }

    private void migrateOneAigen(Path dir, String taskId, MigrationOptions opt, MigrationReport.ModuleReport mr)
            throws IOException {
        AigenTaskEntity entity = findAigen(taskId);
        long userId = entity != null && entity.getUserId() != null ? entity.getUserId() : 0L;
        boolean updated = false;
        Path root = dir.toAbsolutePath().normalize();

        Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                try {
                    if (!Files.isRegularFile(file)) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    String rel = root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
                    if (rel.isBlank() || rel.contains("..")) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    if (rel.startsWith("logs/") && attrs.size() > 5_000_000) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    String key = uploadIfNeeded(userId, "aigen", taskId, file, rel, opt, mr);
                    if (key != null && "output.mp4".equals(rel) && entity != null
                            && needsRewrite(entity.getOutputPath(), key)) {
                        if (!opt.isDryRun()) {
                            entity.setOutputPath(key);
                        }
                    }
                } catch (Exception e) {
                    mr.setFilesFailed(mr.getFilesFailed() + 1);
                    addError(mr, "aigen/" + taskId + "/" + file.getFileName() + ": " + e.getMessage());
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });

        // 若 output 字段仍是本地路径
        if (entity != null && entity.getOutputPath() != null
                && ObjectKeyBuilder.looksLikeLocalAbsolutePath(entity.getOutputPath())) {
            Path out = Path.of(entity.getOutputPath());
            if (Files.isRegularFile(out)) {
                String key = uploadIfNeeded(userId, "aigen", taskId, out, "output.mp4", opt, mr);
                if (key != null && !opt.isDryRun()) {
                    entity.setOutputPath(key);
                    updated = true;
                }
            }
        } else if (entity != null && entity.getOutputPath() != null
                && !ObjectKeyBuilder.looksLikeLocalAbsolutePath(entity.getOutputPath())) {
            // already key
        } else if (entity != null) {
            String key = keyBuilder.build("aigen", userId, taskId, "output.mp4");
            if (objectStorage.exists(key) && !opt.isDryRun()) {
                entity.setOutputPath(key);
                updated = true;
            }
        }

        if (entity != null && (updated || (!opt.isDryRun() && entity.getOutputPath() != null
                && !ObjectKeyBuilder.looksLikeLocalAbsolutePath(entity.getOutputPath())))) {
            if (!opt.isDryRun()) {
                entity.setUpdatedAt(LocalDateTime.now());
                aigenTaskMapper.updateById(entity);
                mr.setTasksUpdated(mr.getTasksUpdated() + 1);
                sample(mr, "aigen " + taskId + " output → " + entity.getOutputPath());
            }
        }

        if (opt.isDeleteLocal() && !opt.isDryRun()) {
            mr.setLocalsDeleted(mr.getLocalsDeleted() + deleteDirIfAllUploaded(dir, userId, "aigen", taskId, mr));
        }
    }

    // ---------- imggen ----------

    private MigrationReport.ModuleReport migrateImggen(MigrationOptions opt) {
        MigrationReport.ModuleReport mr = baseModule("imggen");
        Path root = Path.of(imgGenProperties.getWorkDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            mr.getSamples().add("work-dir 不存在: " + root);
            return mr;
        }
        List<Path> dirs = listTaskDirs(root, opt);
        mr.setScannedDirs(dirs.size());
        for (Path dir : dirs) {
            String taskId = dir.getFileName().toString();
            try {
                migrateOneImggen(dir, taskId, opt, mr);
            } catch (Exception e) {
                mr.setFilesFailed(mr.getFilesFailed() + 1);
                addError(mr, "imggen/" + taskId + ": " + e.getMessage());
            }
            if (hitLimit(opt, mr)) {
                break;
            }
        }
        return mr;
    }

    private void migrateOneImggen(Path dir, String taskId, MigrationOptions opt, MigrationReport.ModuleReport mr)
            throws IOException {
        ImgGenTaskEntity entity = findImggen(taskId);
        long userId = entity != null && entity.getUserId() != null ? entity.getUserId() : 0L;
        boolean updated = false;
        Path outputs = dir.resolve("outputs");
        if (Files.isDirectory(outputs)) {
            try (Stream<Path> list = Files.list(outputs)) {
                for (Path f : list.filter(Files::isRegularFile).toList()) {
                    String rel = "outputs/" + f.getFileName();
                    String key = uploadIfNeeded(userId, "imggen", taskId, f, rel, opt, mr);
                    if (key != null && entity != null) {
                        // cover
                        if (entity.getCoverPath() != null
                                && ObjectKeyBuilder.looksLikeLocalAbsolutePath(entity.getCoverPath())) {
                            try {
                                if (Path.of(entity.getCoverPath()).toAbsolutePath().normalize()
                                        .equals(f.toAbsolutePath().normalize()) && !opt.isDryRun()) {
                                    entity.setCoverPath(key);
                                    updated = true;
                                }
                            } catch (Exception ignored) {
                                // ignore
                            }
                        }
                    }
                }
            }
        }
        // cover 仍本地
        if (entity != null && entity.getCoverPath() != null
                && ObjectKeyBuilder.looksLikeLocalAbsolutePath(entity.getCoverPath())) {
            Path cover = Path.of(entity.getCoverPath());
            if (Files.isRegularFile(cover)) {
                String name = cover.getFileName().toString();
                String key = uploadIfNeeded(userId, "imggen", taskId, cover, "outputs/" + name, opt, mr);
                if (key != null && !opt.isDryRun()) {
                    entity.setCoverPath(key);
                    updated = true;
                }
            }
        } else if (entity != null && (entity.getCoverPath() == null || entity.getCoverPath().isBlank())
                && Files.isDirectory(outputs)) {
            try (Stream<Path> list = Files.list(outputs)) {
                Optional<Path> first = list.filter(Files::isRegularFile).findFirst();
                if (first.isPresent() && !opt.isDryRun()) {
                    String rel = "outputs/" + first.get().getFileName();
                    entity.setCoverPath(keyBuilder.build("imggen", userId, taskId, rel));
                    updated = true;
                }
            }
        }

        if (entity != null && entity.getResultJson() != null && !entity.getResultJson().isBlank() && !opt.isDryRun()) {
            try {
                JsonNode root = objectMapper.readTree(entity.getResultJson());
                if (root instanceof ObjectNode obj && obj.has("images") && obj.get("images").isArray()) {
                    ArrayNode arr = (ArrayNode) obj.get("images");
                    boolean changed = false;
                    for (int i = 0; i < arr.size(); i++) {
                        if (!(arr.get(i) instanceof ObjectNode im)) {
                            continue;
                        }
                        String path = im.has("path") ? im.get("path").asText() : null;
                        if (path == null) {
                            continue;
                        }
                        String fileName = path.contains("/")
                                ? path.substring(path.lastIndexOf('/') + 1) : path;
                        String rel = "outputs/" + fileName;
                        String key = keyBuilder.build("imggen", userId, taskId, rel);
                        im.put("path", rel);
                        im.put("objectKey", key);
                        changed = true;
                    }
                    if (changed) {
                        entity.setResultJson(objectMapper.writeValueAsString(obj));
                        updated = true;
                    }
                }
            } catch (Exception e) {
                addError(mr, "imggen/" + taskId + " resultJson: " + e.getMessage());
            }
        }

        if (entity != null && updated && !opt.isDryRun()) {
            entity.setUpdatedAt(LocalDateTime.now());
            imgGenTaskMapper.updateById(entity);
            mr.setTasksUpdated(mr.getTasksUpdated() + 1);
            sample(mr, "imggen " + taskId + " cover → " + entity.getCoverPath());
        }

        if (opt.isDeleteLocal() && !opt.isDryRun()) {
            mr.setLocalsDeleted(mr.getLocalsDeleted() + deleteDirIfAllUploaded(dir, userId, "imggen", taskId, mr));
        }
    }

    // ---------- helpers ----------

    @FunctionalInterface
    private interface PathSetter {
        void set(VideoTaskEntity e, String key);
    }

    private boolean migratePathField(VideoTaskEntity entity, long userId, String module, String taskId,
                                     String currentPath, Path dir, String defaultName,
                                     MigrationOptions opt, MigrationReport.ModuleReport mr,
                                     PathSetter setter) {
        if (entity == null) {
            Path f = dir.resolve(defaultName);
            if (Files.isRegularFile(f)) {
                uploadIfNeeded(userId, module, taskId, f, defaultName, opt, mr);
            }
            return false;
        }
        if (currentPath != null && !currentPath.isBlank()
                && !ObjectKeyBuilder.looksLikeLocalAbsolutePath(currentPath)) {
            return false; // already key
        }
        Path local = null;
        if (currentPath != null && ObjectKeyBuilder.looksLikeLocalAbsolutePath(currentPath)) {
            try {
                Path p = Path.of(currentPath);
                if (Files.isRegularFile(p)) {
                    local = p;
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (local == null) {
            Path f = dir.resolve(defaultName);
            // audio.*
            if (!Files.isRegularFile(f) && defaultName.startsWith("audio")) {
                try (Stream<Path> list = Files.list(dir)) {
                    local = list.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("audio"))
                            .findFirst().orElse(null);
                } catch (IOException ignored) {
                    // ignore
                }
            } else if (Files.isRegularFile(f)) {
                local = f;
            }
        }
        if (local == null) {
            return false;
        }
        String rel = local.getFileName().toString();
        if (defaultName.endsWith(".json")) {
            rel = defaultName;
        }
        String key = uploadIfNeeded(userId, module, taskId, local, rel, opt, mr);
        if (key != null && needsRewrite(currentPath, key)) {
            if (!opt.isDryRun()) {
                setter.set(entity, key);
            }
            return true;
        }
        return false;
    }

    /**
     * @return object key if uploaded or already exists; null if skipped/failed
     */
    private String uploadIfNeeded(long userId, String module, String taskId, Path file, String relative,
                                  MigrationOptions opt, MigrationReport.ModuleReport mr) {
        try {
            String key = keyBuilder.build(module, userId, taskId, relative.replace('\\', '/'));
            if (objectStorage.exists(key)) {
                Optional<ObjectMeta> head = objectStorage.head(key);
                long localSize = Files.size(file);
                if (head.isPresent() && head.get().getSizeBytes() == localSize) {
                    mr.setFilesSkipped(mr.getFilesSkipped() + 1);
                    return key;
                }
            }
            if (opt.isDryRun()) {
                mr.setFilesUploaded(mr.getFilesUploaded() + 1);
                sample(mr, "[dry-run] put " + key + " (" + Files.size(file) + " bytes)");
                return key;
            }
            objectStorage.put(key, file, LocalObjectStorage.guessContentType(relative));
            Optional<ObjectMeta> head = objectStorage.head(key);
            if (head.isEmpty() || head.get().getSizeBytes() != Files.size(file)) {
                mr.setFilesFailed(mr.getFilesFailed() + 1);
                addError(mr, "校验失败: " + key);
                return null;
            }
            mr.setFilesUploaded(mr.getFilesUploaded() + 1);
            return key;
        } catch (Exception e) {
            mr.setFilesFailed(mr.getFilesFailed() + 1);
            addError(mr, relative + ": " + e.getMessage());
            return null;
        }
    }

    private int deleteDirIfAllUploaded(Path dir, long userId, String module, String taskId,
                                       MigrationReport.ModuleReport mr) {
        try {
            // 简单策略：目录内每个文件都有对应 key 再删
            Path root = dir.toAbsolutePath().normalize();
            List<Path> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile).forEach(files::add);
            }
            for (Path f : files) {
                String rel = root.relativize(f.toAbsolutePath().normalize()).toString().replace('\\', '/');
                String key = keyBuilder.build(module, userId, taskId, rel);
                if (!objectStorage.exists(key)) {
                    addError(mr, "未删本地(缺对象): " + key);
                    return 0;
                }
            }
            int deleted = 0;
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    if (Files.deleteIfExists(p)) {
                        deleted++;
                    }
                }
            }
            return deleted;
        } catch (Exception e) {
            addError(mr, "删除本地失败 " + dir + ": " + e.getMessage());
            return 0;
        }
    }

    private static boolean needsRewrite(String current, String newKey) {
        if (newKey == null || newKey.isBlank()) {
            return false;
        }
        if (current == null || current.isBlank()) {
            return true;
        }
        return ObjectKeyBuilder.looksLikeLocalAbsolutePath(current) || !current.equals(newKey);
    }

    private List<Path> listTaskDirs(Path root, MigrationOptions opt) {
        try (Stream<Path> list = Files.list(root)) {
            Stream<Path> s = list.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()));
            if (opt.getTaskId() != null && !opt.getTaskId().isBlank()) {
                String tid = opt.getTaskId().trim();
                s = s.filter(p -> tid.equals(p.getFileName().toString()));
            }
            List<Path> all = s.toList();
            if (opt.getLimitPerModule() > 0 && all.size() > opt.getLimitPerModule()) {
                return all.subList(0, opt.getLimitPerModule());
            }
            return all;
        } catch (IOException e) {
            throw new BusinessException("列举目录失败: " + root + " — " + e.getMessage());
        }
    }

    private boolean hitLimit(MigrationOptions opt, MigrationReport.ModuleReport mr) {
        return opt.getLimitPerModule() > 0
                && (mr.getTasksUpdated() + mr.getFilesUploaded()) >= opt.getLimitPerModule() * 50L;
    }

    private VideoTaskEntity findVideo(String taskId) {
        try {
            return videoTaskMapper.selectById(Long.parseLong(taskId));
        } catch (Exception e) {
            return null;
        }
    }

    private AigenTaskEntity findAigen(String taskId) {
        try {
            return aigenTaskMapper.selectById(Long.parseLong(taskId));
        } catch (Exception e) {
            return null;
        }
    }

    private ImgGenTaskEntity findImggen(String taskId) {
        try {
            return imgGenTaskMapper.selectById(Long.parseLong(taskId));
        } catch (Exception e) {
            return null;
        }
    }

    private static MigrationReport.ModuleReport baseModule(String name) {
        return MigrationReport.ModuleReport.builder()
                .module(name)
                .errors(new ArrayList<>())
                .samples(new ArrayList<>())
                .build();
    }

    private static void addError(MigrationReport.ModuleReport mr, String msg) {
        if (mr.getErrors().size() < 50) {
            mr.getErrors().add(msg);
        }
        log.warn("migration: {}", msg);
    }

    private static void sample(MigrationReport.ModuleReport mr, String msg) {
        if (mr.getSamples().size() < 20) {
            mr.getSamples().add(msg);
        }
    }
}
