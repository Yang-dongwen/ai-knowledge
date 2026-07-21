package com.dwcode.okxbot.storage;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.storage.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 本地磁盘实现的对象存储：key 映射为 {@code {storage.local.root}/{key}}。
 * <p>PR1 默认 provider；行为便于单测与无 R2 环境开发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalObjectStorage implements ObjectStoragePort {

    private final StorageProperties storageProperties;

    @Override
    public String providerId() {
        return "local";
    }

    @Override
    public void put(String key, Path localFile, String contentType) {
        if (localFile == null || !Files.isRegularFile(localFile)) {
            throw new BusinessException(400, "put 源文件不存在: " + localFile);
        }
        Path dest = resolveKeyPath(key);
        try {
            Files.createDirectories(dest.getParent());
            Files.copy(localFile, dest, StandardCopyOption.REPLACE_EXISTING);
            log.debug("local put file: key={}, bytes={}", key, Files.size(dest));
        } catch (IOException e) {
            throw new BusinessException("local put 失败: " + key + " — " + e.getMessage());
        }
    }

    @Override
    public void putBytes(String key, byte[] data, String contentType) {
        if (data == null) {
            throw new BusinessException(400, "putBytes data 不能为 null");
        }
        Path dest = resolveKeyPath(key);
        try {
            Files.createDirectories(dest.getParent());
            Files.write(dest, data);
            log.debug("local put bytes: key={}, bytes={}", key, data.length);
        } catch (IOException e) {
            throw new BusinessException("local putBytes 失败: " + key + " — " + e.getMessage());
        }
    }

    @Override
    public void getToFile(String key, Path localFile) {
        Path src = requireExisting(key);
        try {
            if (localFile.getParent() != null) {
                Files.createDirectories(localFile.getParent());
            }
            Files.copy(src, localFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException("local getToFile 失败: " + key + " — " + e.getMessage());
        }
    }

    @Override
    public InputStream openStream(String key) {
        Path src = requireExisting(key);
        try {
            return Files.newInputStream(src);
        } catch (IOException e) {
            throw new BusinessException("local openStream 失败: " + key + " — " + e.getMessage());
        }
    }

    @Override
    public InputStream openStream(String key, long startInclusive, long endInclusive) {
        Path src = requireExisting(key);
        try {
            long size = Files.size(src);
            long start = Math.max(0, startInclusive);
            if (start >= size) {
                throw new BusinessException(416, "Requested Range Not Satisfiable");
            }
            long end = Math.min(endInclusive, size - 1);
            long len = end - start + 1;
            InputStream in = Files.newInputStream(src);
            if (start > 0) {
                in.skipNBytes(start);
            }
            return new com.dwcode.okxbot.common.web.LimitedInputStream(in, len);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException("local openStream(range) 失败: " + key + " — " + e.getMessage());
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            Path p = resolveKeyPath(key);
            return Files.isRegularFile(p);
        } catch (BusinessException e) {
            return false;
        }
    }

    @Override
    public void delete(String key) {
        Path p = resolveKeyPath(key);
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new BusinessException("local delete 失败: " + key + " — " + e.getMessage());
        }
    }

    @Override
    public int deletePrefix(String prefix) {
        String pfx = normalizePrefix(prefix);
        Path root = resolveRoot();
        Path base = root.resolve(pfx).normalize();
        if (!base.startsWith(root) || base.equals(root)) {
            // prefix 可能是 "dev/video/1/2/"，对应目录
            throw new BusinessException(400, "非法 deletePrefix: " + prefix);
        }
        if (!Files.exists(base)) {
            // 也可能 prefix 对应的是「虚拟目录」——用 walk 从 root 过滤
            return deleteByKeyPrefix(pfx);
        }
        if (Files.isRegularFile(base)) {
            try {
                return Files.deleteIfExists(base) ? 1 : 0;
            } catch (IOException e) {
                throw new BusinessException("local deletePrefix 失败: " + e.getMessage());
            }
        }
        int[] count = {0};
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.deleteIfExists(file)) {
                        count[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (Files.deleteIfExists(dir)) {
                        count[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new BusinessException("local deletePrefix 失败: " + e.getMessage());
        }
        return count[0];
    }

    private int deleteByKeyPrefix(String pfx) {
        Path root = resolveRoot();
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int[] count = {0};
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        String key = toKey(file);
                        if (key != null && key.startsWith(pfx)) {
                            try {
                                if (Files.deleteIfExists(file)) {
                                    count[0]++;
                                }
                            } catch (IOException e) {
                                log.warn("deleteByKeyPrefix 跳过: {} — {}", file, e.getMessage());
                            }
                        }
                    });
        } catch (IOException e) {
            throw new BusinessException("local deletePrefix 扫描失败: " + e.getMessage());
        }
        return count[0];
    }

    @Override
    public Optional<ObjectMeta> head(String key) {
        Path p;
        try {
            p = resolveKeyPath(key);
        } catch (BusinessException e) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(p)) {
            return Optional.empty();
        }
        try {
            return Optional.of(ObjectMeta.builder()
                    .key(normalizeKey(key))
                    .sizeBytes(Files.size(p))
                    .contentType(guessContentType(p.getFileName().toString()))
                    .lastModifiedEpochMs(Files.getLastModifiedTime(p).toMillis())
                    .build());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public URI presignGet(String key, Duration ttl, boolean attachment, String downloadName) {
        throw new BusinessException(400, "local 存储不支持预签名 URL，请使用后端代理下载");
    }

    @Override
    public URI presignPut(String key, Duration ttl, String contentType) {
        throw new BusinessException(400, "local 存储不支持预签名上传");
    }

    /**
     * 将 key 解析为绝对路径，并保证落在 local.root 内。
     */
    Path resolveKeyPath(String key) {
        String k = normalizeKey(key);
        Path root = resolveRoot();
        Path resolved = root.resolve(k).normalize();
        if (!resolved.startsWith(root)) {
            throw new BusinessException(400, "object key 越界: " + key);
        }
        return resolved;
    }

    Path resolveRoot() {
        return Path.of(storageProperties.getLocal().getRoot()).toAbsolutePath().normalize();
    }

    private Path requireExisting(String key) {
        Path p = resolveKeyPath(key);
        if (!Files.isRegularFile(p)) {
            throw new BusinessException(404, "对象不存在: " + key);
        }
        return p;
    }

    private String toKey(Path file) {
        Path root = resolveRoot();
        Path abs = file.toAbsolutePath().normalize();
        if (!abs.startsWith(root)) {
            return null;
        }
        return root.relativize(abs).toString().replace('\\', '/');
    }

    public static String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(400, "object key 不能为空");
        }
        String k = key.trim().replace('\\', '/');
        while (k.startsWith("/")) {
            k = k.substring(1);
        }
        if (k.isEmpty() || k.contains("..") || k.contains(":")) {
            throw new BusinessException(400, "非法 object key: " + key);
        }
        return k.replaceAll("/+", "/");
    }

    private static String normalizePrefix(String prefix) {
        String p = normalizeKey(prefix);
        if (!p.endsWith("/")) {
            p = p + "/";
        }
        return p;
    }

    public static String guessContentType(String fileName) {
        if (fileName == null) {
            return "application/octet-stream";
        }
        String n = fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (n.endsWith(".webm")) {
            return "video/webm";
        }
        if (n.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (n.endsWith(".m4a")) {
            return "audio/mp4";
        }
        if (n.endsWith(".wav")) {
            return "audio/wav";
        }
        if (n.endsWith(".png")) {
            return "image/png";
        }
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (n.endsWith(".webp")) {
            return "image/webp";
        }
        if (n.endsWith(".json")) {
            return "application/json";
        }
        if (n.endsWith(".txt")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    /** 测试/调试：把 InputStream 写到 dest（未使用 openStream 拷贝时可用） */
    @SuppressWarnings("unused")
    private static void copyStream(InputStream in, Path dest) throws IOException {
        try (InputStream input = in; OutputStream out = Files.newOutputStream(dest)) {
            input.transferTo(out);
        }
    }
}
