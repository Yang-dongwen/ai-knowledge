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
import com.dwcode.okxbot.common.web.MediaRangeSupport;
import com.dwcode.okxbot.storage.ObjectKeyBuilder;
import com.dwcode.okxbot.storage.ObjectStoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class KbFileService {

    private final KbFileMapper fileMapper;
    private final KbNoteMapper noteMapper;
    private final ObjectStoragePort objectStorage;
    private final ObjectKeyBuilder objectKeyBuilder;
    private final KbProperties kbProperties;

    /**
     * 整包 multipart 上传（小文件 / 小程序兼容）。大文件走 {@link KbFileUploadService} 分片。
     */
    public FileResponse upload(MultipartFile file, Long noteId) {
        Long userId = SecurityUtils.requireCurrentUserId();
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "文件不能为空");
        }
        String original = KbFileRules.sanitizeOriginalName(file.getOriginalFilename());
        String contentType = KbFileRules.normalizeContentType(file.getContentType());
        String kind = KbFileRules.detectKind(KbFileRules.extensionOf(original), contentType);
        long size = file.getSize();
        KbFileRules.requireSize(size, kbProperties.getFile().getMaxDirectBytes(), "file");

        if (noteId != null) {
            requireNoteOwned(noteId, userId);
        }

        KbFileEntity entity = new KbFileEntity();
        entity.setUserId(userId);
        entity.setNoteId(noteId);
        entity.setOriginalName(original);
        entity.setContentType(contentType);
        entity.setSizeBytes(size);
        entity.setKind(kind);
        entity.setObjectKey("pending");
        fileMapper.insert(entity);

        String safeName = KbFileRules.sanitizeFileName(original);
        String key = objectKeyBuilder.build("kb", userId, String.valueOf(entity.getId()), safeName);
        try (InputStream in = file.getInputStream()) {
            objectStorage.putStream(key, in, size, contentType);
        } catch (IOException e) {
            fileMapper.deleteById(entity.getId());
            throw new BusinessException(500, "上传失败: " + e.getMessage());
        } catch (RuntimeException e) {
            fileMapper.deleteById(entity.getId());
            try {
                objectStorage.delete(key);
            } catch (Exception del) {
                log.warn("direct upload 回滚删对象失败 fileId={}: {}", entity.getId(), del.getMessage());
            }
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

    public List<KbFileEntity> listEntitiesByNote(Long noteId, Long userId) {
        if (noteId == null) {
            return List.of();
        }
        return fileMapper.selectList(
                new LambdaQueryWrapper<KbFileEntity>()
                        .eq(KbFileEntity::getUserId, userId)
                        .eq(KbFileEntity::getNoteId, noteId)
                        .orderByDesc(KbFileEntity::getCreatedAt));
    }

    public KbFileEntity requireOwnedEntity(Long id, Long userId) {
        return requireOwned(id, userId);
    }

    public byte[] readBytes(KbFileEntity e) {
        if (e == null || !StringUtils.hasText(e.getObjectKey()) || "pending".equals(e.getObjectKey())) {
            throw new BusinessException(404, "文件内容不存在");
        }
        if (!objectStorage.exists(e.getObjectKey())) {
            throw new BusinessException(404, "文件内容不存在");
        }
        try (InputStream in = objectStorage.openStream(e.getObjectKey())) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new BusinessException(500, "读取附件失败: " + ex.getMessage());
        }
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

    public ResponseEntity<Resource> streamContent(Long id, boolean download, String rangeHeader) {
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
        long len = e.getSizeBytes() != null ? e.getSizeBytes() : -1;

        if (resolved != null && "video".equalsIgnoreCase(resolved.getType()) && len > 0) {
            try {
                in.close();
            } catch (Exception ignored) {
                // reopen ranged stream below
            }
            final String key = e.getObjectKey();
            return MediaRangeSupport.build(
                    rangeHeader,
                    len,
                    mediaType.toString(),
                    safeName,
                    (start, end) -> objectStorage.openStream(key, start, end),
                    !inline);
        }

        ResponseEntity.BodyBuilder bb = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, MediaRangeSupport.contentDisposition(!inline, safeName))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=120")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(mediaType);
        if (len >= 0) {
            bb = bb.contentLength(len);
        }
        return bb.body(MediaRangeSupport.streamingResource(in, len, safeName));
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

    /** 去掉 charset 等参数，仅保留 type/subtype */
    static String baseMime(String contentType) {
        return KbFileRules.baseMime(contentType);
    }

    static String detectKind(String ext, String contentType) {
        return KbFileRules.detectKind(ext, contentType);
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
