package com.dwcode.okxbot.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.kb.config.KbProperties;
import com.dwcode.okxbot.kb.dto.FileResponse;
import com.dwcode.okxbot.kb.entity.KbFileEntity;
import com.dwcode.okxbot.kb.entity.KbNoteEntity;
import com.dwcode.okxbot.kb.mapper.KbFileMapper;
import com.dwcode.okxbot.kb.mapper.KbNoteMapper;
import com.dwcode.okxbot.storage.ObjectKeyBuilder;
import com.dwcode.okxbot.storage.ObjectStoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class KbFileService {

    /** 可执行/脚本/可在浏览器内执行的活动内容，禁止上传 */
    private static final Set<String> BLOCKED_EXT = Set.of(
            "exe", "bat", "cmd", "sh", "ps1", "js", "msi", "dll", "com", "scr", "vbs",
            "html", "htm", "svg", "xhtml", "xml", "mhtml", "shtml", "xht"
    );

    /** 客户端声明的危险 MIME（同 origin 下可能被浏览器当文档执行） */
    private static final Set<String> BLOCKED_MIME = Set.of(
            "text/html",
            "image/svg+xml",
            "application/xhtml+xml",
            "text/xml",
            "application/xml",
            "application/javascript",
            "text/javascript",
            "text/css"
    );

    private final KbFileMapper fileMapper;
    private final KbNoteMapper noteMapper;
    private final ObjectStoragePort objectStorage;
    private final ObjectKeyBuilder objectKeyBuilder;
    private final KbProperties kbProperties;

    @Transactional
    public FileResponse upload(MultipartFile file, Long noteId) {
        Long userId = SecurityUtils.requireCurrentUserId();
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "文件不能为空");
        }
        String original = file.getOriginalFilename();
        if (!StringUtils.hasText(original)) {
            original = "file.bin";
        }
        original = original.replace("\\", "/");
        if (original.contains("/")) {
            original = original.substring(original.lastIndexOf('/') + 1);
        }
        if (original.length() > 200) {
            original = original.substring(original.length() - 200);
        }

        String ext = extensionOf(original);
        if (BLOCKED_EXT.contains(ext)) {
            throw new BusinessException(400, "不允许上传该类型文件");
        }

        String contentType = file.getContentType();
        if (!StringUtils.hasText(contentType)) {
            contentType = "application/octet-stream";
        }
        String mimeBase = baseMime(contentType);
        if (BLOCKED_MIME.contains(mimeBase)) {
            throw new BusinessException(400, "不允许上传该类型文件");
        }
        String kind = detectKind(ext, contentType);
        long size = file.getSize();
        validateSize(kind, size);

        if (noteId != null) {
            requireNoteOwned(noteId, userId);
        }

        // 先拿 id：MyBatis ASSIGN_ID 在 insert 时生成
        KbFileEntity entity = new KbFileEntity();
        entity.setUserId(userId);
        entity.setNoteId(noteId);
        entity.setOriginalName(original);
        entity.setContentType(contentType);
        entity.setSizeBytes(size);
        entity.setKind(kind);
        entity.setObjectKey("pending");
        fileMapper.insert(entity);

        String safeName = sanitizeFileName(original);
        String key = objectKeyBuilder.build("kb", userId, String.valueOf(entity.getId()), safeName);
        try {
            objectStorage.putBytes(key, file.getBytes(), contentType);
        } catch (IOException e) {
            fileMapper.deleteById(entity.getId());
            throw new BusinessException(500, "上传失败: " + e.getMessage());
        } catch (RuntimeException e) {
            fileMapper.deleteById(entity.getId());
            throw e;
        }
        entity.setObjectKey(key);
        fileMapper.updateById(entity);
        log.info("kb file uploaded userId={} fileId={} kind={} size={}", userId, entity.getId(), kind, size);
        return toResponse(entity);
    }

    public List<FileResponse> listByNote(Long noteId) {
        Long userId = SecurityUtils.requireCurrentUserId();
        if (noteId == null) {
            throw new BusinessException(400, "noteId 不能为空");
        }
        requireNoteOwned(noteId, userId);
        return fileMapper.selectList(
                        new LambdaQueryWrapper<KbFileEntity>()
                                .eq(KbFileEntity::getUserId, userId)
                                .eq(KbFileEntity::getNoteId, noteId)
                                .orderByDesc(KbFileEntity::getCreatedAt))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public FileResponse getMeta(Long id) {
        Long userId = SecurityUtils.requireCurrentUserId();
        return toResponse(requireOwned(id, userId));
    }

    @Transactional
    public FileResponse bind(Long id, Long noteId) {
        Long userId = SecurityUtils.requireCurrentUserId();
        KbFileEntity e = requireOwned(id, userId);
        requireNoteOwned(noteId, userId);
        e.setNoteId(noteId);
        fileMapper.updateById(e);
        return toResponse(e);
    }

    @Transactional
    public void delete(Long id) {
        Long userId = SecurityUtils.requireCurrentUserId();
        KbFileEntity e = requireOwned(id, userId);
        purgeFile(e);
    }

    /**
     * 永久删除某笔记下全部附件（库记录 + R2/本地对象）。
     *
     * @return 删除文件数
     */
    @Transactional
    public int deleteAllForNote(Long userId, Long noteId) {
        if (noteId == null) {
            return 0;
        }
        List<KbFileEntity> list = fileMapper.selectList(
                new LambdaQueryWrapper<KbFileEntity>()
                        .eq(KbFileEntity::getUserId, userId)
                        .eq(KbFileEntity::getNoteId, noteId));
        for (KbFileEntity e : list) {
            purgeFile(e);
        }
        return list.size();
    }

    /**
     * 按 id 列表永久删除（须属当前用户），用于正文中引用的媒体。
     */
    @Transactional
    public int deleteByIds(Long userId, Iterable<Long> ids) {
        int n = 0;
        if (ids == null) {
            return 0;
        }
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            KbFileEntity e = fileMapper.selectById(id);
            if (e == null || !Objects.equals(e.getUserId(), userId)) {
                continue;
            }
            purgeFile(e);
            n++;
        }
        return n;
    }

    private void purgeFile(KbFileEntity e) {
        Long id = e.getId();
        Long userId = e.getUserId();
        try {
            if (StringUtils.hasText(e.getObjectKey()) && !"pending".equals(e.getObjectKey())) {
                objectStorage.delete(e.getObjectKey());
            }
        } catch (Exception ex) {
            log.warn("删除对象失败 fileId={} key={}: {}", id, e.getObjectKey(), ex.getMessage());
        }
        // 同 fileId 目录下残留对象一并清掉（R2/local 前缀删除）
        try {
            if (userId != null && id != null) {
                String prefix = objectKeyBuilder.taskPrefix("kb", userId, String.valueOf(id));
                int deleted = objectStorage.deletePrefix(prefix);
                if (deleted > 0) {
                    log.info("kb file prefix purged fileId={} count={}", id, deleted);
                }
            }
        } catch (Exception ex) {
            log.warn("删除对象前缀失败 fileId={}: {}", id, ex.getMessage());
        }
        fileMapper.deleteById(id);
    }

    public ResponseEntity<InputStreamResource> streamContent(Long id, boolean download) {
        Long userId = SecurityUtils.requireCurrentUserId();
        KbFileEntity e = requireOwned(id, userId);
        if (!objectStorage.exists(e.getObjectKey())) {
            throw new BusinessException(404, "文件内容不存在");
        }
        InputStream raw = objectStorage.openStream(e.getObjectKey());
        java.io.BufferedInputStream in = new java.io.BufferedInputStream(raw);
        byte[] header;
        try {
            in.mark(40);
            header = in.readNBytes(32);
            in.reset();
        } catch (Exception ex) {
            header = new byte[0];
        }
        // 魔数优先：nosniff 下扩展名/上传 MIME 与真实内容不一致会导致浏览器拒显
        MediaType resolved = KbMediaTypes.resolve(e, header);
        // 仅栅格图/PDF/视频可 inline；其余强制 attachment + octet-stream，防 HTML/SVG XSS
        boolean inline = !download && KbMediaTypes.isSafeInline(resolved);
        MediaType mediaType = KbMediaTypes.responseMediaType(resolved);
        String safeName = e.getOriginalName() == null ? "file" : e.getOriginalName().replace("\"", "");
        String encoded = URLEncoder.encode(safeName, StandardCharsets.UTF_8).replace("+", "%20");
        String disposition = (inline ? "inline" : "attachment")
                + "; filename=\"" + safeName + "\"; filename*=UTF-8''" + encoded;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=120")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(mediaType)
                .contentLength(e.getSizeBytes() != null ? e.getSizeBytes() : -1)
                .body(new InputStreamResource(in));
    }

    /** @deprecated 使用 {@link KbMediaTypes#resolve(KbFileEntity)} */
    static MediaType resolveMediaType(KbFileEntity e) {
        return KbMediaTypes.resolve(e);
    }

    private KbFileEntity requireOwned(Long id, Long userId) {
        KbFileEntity e = fileMapper.selectById(id);
        if (e == null || !Objects.equals(e.getUserId(), userId)) {
            throw new BusinessException(404, "文件不存在");
        }
        return e;
    }

    private void requireNoteOwned(Long noteId, Long userId) {
        KbNoteEntity n = noteMapper.selectById(noteId);
        if (n == null || !Objects.equals(n.getUserId(), userId)) {
            throw new BusinessException(404, "笔记不存在");
        }
    }

    private void validateSize(String kind, long size) {
        KbProperties.File conf = kbProperties.getFile();
        long max = switch (kind) {
            case "image" -> conf.getMaxImageBytes();
            case "video" -> conf.getMaxVideoBytes();
            default -> conf.getMaxOtherBytes();
        };
        if (size > max) {
            throw new BusinessException(400, "文件过大，上限 " + (max / 1024 / 1024) + "MB");
        }
    }

    /** 去掉 charset 等参数，仅保留 type/subtype */
    static String baseMime(String contentType) {
        if (contentType == null) {
            return "";
        }
        String ct = contentType.trim().toLowerCase(Locale.ROOT);
        int semi = ct.indexOf(';');
        if (semi >= 0) {
            ct = ct.substring(0, semi).trim();
        }
        return ct;
    }

    static String detectKind(String ext, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        // svg 已在 BLOCKED_EXT 拒绝，不归类为 image
        if (ct.startsWith("image/") || Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp").contains(ext)) {
            return "image";
        }
        if (ct.startsWith("video/") || Set.of("mp4", "webm", "mov", "mkv").contains(ext)) {
            return "video";
        }
        if (ct.startsWith("audio/") || Set.of("mp3", "wav", "ogg", "m4a").contains(ext)) {
            return "audio";
        }
        if ("pdf".equals(ext) || ct.contains("pdf")) {
            return "pdf";
        }
        if (Set.of("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp").contains(ext)
                || ct.contains("officedocument") || ct.contains("msword") || ct.contains("ms-excel")
                || ct.contains("ms-powerpoint")) {
            return "office";
        }
        return "other";
    }

    private static String extensionOf(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) {
            return "";
        }
        return name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static String sanitizeFileName(String name) {
        String n = name.replaceAll("[^A-Za-z0-9._@+-]", "_");
        if (n.isBlank()) {
            n = "file.bin";
        }
        if (n.length() > 120) {
            n = n.substring(n.length() - 120);
        }
        return n;
    }

    private FileResponse toResponse(KbFileEntity e) {
        return FileResponse.builder()
                .id(e.getId())
                .noteId(e.getNoteId())
                .originalName(e.getOriginalName())
                .contentType(e.getContentType())
                .sizeBytes(e.getSizeBytes() == null ? 0 : e.getSizeBytes())
                .kind(e.getKind())
                .contentPath("/api/v1/kb/files/" + e.getId() + "/content")
                .createdAt(e.getCreatedAt())
                .build();
    }
}
