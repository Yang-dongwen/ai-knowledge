package com.dwcode.okxbot.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.kb.config.KbProperties;
import com.dwcode.okxbot.kb.dto.FileResponse;
import com.dwcode.okxbot.kb.dto.FileUploadInitRequest;
import com.dwcode.okxbot.kb.dto.FileUploadPartResponse;
import com.dwcode.okxbot.kb.dto.FileUploadSessionResponse;
import com.dwcode.okxbot.kb.entity.KbFileEntity;
import com.dwcode.okxbot.kb.entity.KbFileUploadEntity;
import com.dwcode.okxbot.kb.entity.KbNoteEntity;
import com.dwcode.okxbot.kb.mapper.KbFileMapper;
import com.dwcode.okxbot.kb.mapper.KbFileUploadMapper;
import com.dwcode.okxbot.kb.mapper.KbNoteMapper;
import com.dwcode.okxbot.storage.ObjectKeyBuilder;
import com.dwcode.okxbot.storage.ObjectStoragePort;
import com.dwcode.okxbot.storage.ScratchWorkspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KbFileUploadService {

    static final String SCRATCH_MODULE = "kb";

    private final KbFileUploadMapper uploadMapper;
    private final KbFileMapper fileMapper;
    private final KbNoteMapper noteMapper;
    private final ObjectStoragePort objectStorage;
    private final ObjectKeyBuilder objectKeyBuilder;
    private final ScratchWorkspace scratchWorkspace;
    private final KbProperties kbProperties;
    private final KbUploadLimiter limiter;

    public FileUploadSessionResponse init(FileUploadInitRequest request) {
        Long userId = SecurityUtils.requireCurrentUserId();
        String original = KbFileRules.sanitizeOriginalName(request.getOriginalName());
        String contentType = KbFileRules.normalizeContentType(request.getContentType());
        long total = request.getSizeBytes() == null ? 0 : request.getSizeBytes();
        KbProperties.File conf = kbProperties.getFile();
        KbFileRules.requireSize(total, conf.getMaxBytes(), "file");
        if (request.getNoteId() != null) {
            requireNoteOwned(request.getNoteId(), userId);
        }
        int chunkSize = conf.getChunkSizeBytes();
        if (chunkSize < 1024) {
            throw new BusinessException(400, "分片大小过小");
        }
        int totalParts = (int) ((total + chunkSize - 1) / chunkSize);
        if (totalParts < 1 || totalParts > conf.getMaxParts()) {
            throw new BusinessException(400, "分片数不合法");
        }

        limiter.acquireSession(userId);
        try {
            KbFileUploadEntity e = new KbFileUploadEntity();
            e.setUserId(userId);
            e.setNoteId(request.getNoteId());
            e.setOriginalName(original);
            e.setContentType(contentType);
            e.setKind(KbFileRules.detectKind(KbFileRules.extensionOf(original), contentType));
            e.setTotalBytes(total);
            e.setChunkSize(chunkSize);
            e.setTotalParts(totalParts);
            e.setStatus(KbFileUploadEntity.STATUS_PENDING);
            e.setExpiresAt(LocalDateTime.now().plusHours(Math.max(1, conf.getSessionTtlHours())));
            uploadMapper.insert(e);
            scratchWorkspace.openTaskScratch(SCRATCH_MODULE, scratchTaskId(e.getId()));
            log.info("kb upload init userId={} uploadId={} parts={} size={}",
                    userId, e.getId(), totalParts, total);
            return toSession(e);
        } catch (RuntimeException ex) {
            limiter.releaseSession(userId);
            throw ex;
        }
    }

    public FileUploadSessionResponse get(Long uploadId) {
        Long userId = SecurityUtils.requireCurrentUserId();
        KbFileUploadEntity e = requireOwned(uploadId, userId);
        return toSession(e);
    }

    /**
     * 未完成分片会话，用于离开页面后再回来恢复「暂停」列表。
     *
     * @param noteId  指定笔记；与 {@code unbound}=true 互斥优先 unbound
     * @param unbound true 时只列尚未绑定笔记的会话
     */
    public List<FileUploadSessionResponse> listPending(Long noteId, boolean unbound) {
        Long userId = SecurityUtils.requireCurrentUserId();
        LambdaQueryWrapper<KbFileUploadEntity> q = new LambdaQueryWrapper<KbFileUploadEntity>()
                .eq(KbFileUploadEntity::getUserId, userId)
                .eq(KbFileUploadEntity::getStatus, KbFileUploadEntity.STATUS_PENDING)
                .orderByDesc(KbFileUploadEntity::getUpdatedAt)
                .last("LIMIT 50");
        if (unbound || noteId == null) {
            q.isNull(KbFileUploadEntity::getNoteId);
        } else {
            q.eq(KbFileUploadEntity::getNoteId, noteId);
        }
        return uploadMapper.selectList(q).stream().map(this::toSession).toList();
    }

    public FileUploadPartResponse putPart(Long uploadId, int partNumber, InputStream body, Long contentLength) {
        Long userId = SecurityUtils.requireCurrentUserId();
        if (partNumber < 1) {
            throw new BusinessException(400, "分片号从 1 开始");
        }
        KbFileUploadEntity e = requireOwned(uploadId, userId);
        if (!KbFileUploadEntity.STATUS_PENDING.equals(e.getStatus())) {
            throw new BusinessException(409, "上传会话已结束");
        }
        if (partNumber > e.getTotalParts()) {
            throw new BusinessException(400, "分片号超出范围");
        }
        long expected = expectedPartSize(e, partNumber);
        if (contentLength != null && contentLength >= 0 && contentLength != expected) {
            throw new BusinessException(400, "分片大小不匹配，期望 " + expected + " 字节");
        }
        try (KbUploadLimiter.Permit ignored = limiter.acquirePart(userId)) {
            Path dir = scratchWorkspace.openTaskScratch(SCRATCH_MODULE, scratchTaskId(e.getId()));
            Path dest = dir.resolve(partFileName(partNumber));
            if (isCompletePart(dest, expected)) {
                try {
                    body.transferTo(OutputStream.nullOutputStream());
                } catch (IOException ignoredDrain) {
                    // 重传分片已存在，排空请求体即可
                }
            } else {
                Path tmp = dir.resolve(partFileName(partNumber) + ".tmp." + UUID.randomUUID());
                try {
                    copyExactly(body, tmp, expected);
                    try {
                        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (IOException moveEx) {
                        if (isCompletePart(dest, expected)) {
                            Files.deleteIfExists(tmp);
                        } else {
                            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } catch (BusinessException | IOException ex) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException ignored2) {
                        // ignore
                    }
                    if (isCompletePart(dest, expected)) {
                        // 另一线程已写入
                    } else if (ex instanceof BusinessException be) {
                        throw be;
                    } else {
                        throw new BusinessException(500, "写入分片失败: " + ex.getMessage());
                    }
                }
            }
        }
        List<Integer> received = listReceivedParts(e);
        long uploaded = uploadedBytes(e, received);
        return FileUploadPartResponse.builder()
                .uploadId(e.getId())
                .partNumber(partNumber)
                .partBytes(expected)
                .receivedParts(received)
                .uploadedBytes(uploaded)
                .totalParts(e.getTotalParts())
                .build();
    }

    public FileResponse complete(Long uploadId) {
        Long userId = SecurityUtils.requireCurrentUserId();
        KbFileUploadEntity e = requireOwned(uploadId, userId);
        if (KbFileUploadEntity.STATUS_COMPLETED.equals(e.getStatus()) && e.getFileId() != null) {
            KbFileEntity existing = fileMapper.selectById(e.getFileId());
            if (existing != null) {
                return toFileResponse(existing);
            }
        }
        if (!KbFileUploadEntity.STATUS_PENDING.equals(e.getStatus())) {
            throw new BusinessException(409, "上传会话不可完成");
        }

        try (KbUploadLimiter.Permit ignored = limiter.acquireComplete()) {
            int cas = uploadMapper.casStatus(e.getId(),
                    KbFileUploadEntity.STATUS_PENDING, KbFileUploadEntity.STATUS_COMPLETING);
            if (cas != 1) {
                throw new BusinessException(409, "上传会话正在完成或已结束");
            }
            e.setStatus(KbFileUploadEntity.STATUS_COMPLETING);
            try {
                List<Path> parts = requireAllParts(e);
                KbFileEntity file = new KbFileEntity();
                file.setUserId(userId);
                file.setNoteId(e.getNoteId());
                file.setOriginalName(e.getOriginalName());
                file.setContentType(e.getContentType());
                file.setSizeBytes(e.getTotalBytes());
                file.setKind(e.getKind());
                file.setObjectKey("pending");
                fileMapper.insert(file);

                String safeName = KbFileRules.sanitizeFileName(e.getOriginalName());
                String key = objectKeyBuilder.build("kb", userId, String.valueOf(file.getId()), safeName);
                try (InputStream in = new SequentialFilesInputStream(parts)) {
                    objectStorage.putStream(key, in, e.getTotalBytes(), e.getContentType());
                } catch (Exception ex) {
                    fileMapper.deleteById(file.getId());
                    try {
                        objectStorage.delete(key);
                    } catch (Exception del) {
                        log.warn("complete 回滚删对象失败 fileId={}: {}", file.getId(), del.getMessage());
                    }
                    throw (ex instanceof BusinessException be)
                            ? be
                            : new BusinessException(500, "合并上传失败: " + ex.getMessage());
                }

                file.setObjectKey(key);
                fileMapper.updateById(file);
                e.setFileId(file.getId());
                e.setStatus(KbFileUploadEntity.STATUS_COMPLETED);
                uploadMapper.updateById(e);
                scratchWorkspace.cleanupScratch(SCRATCH_MODULE, scratchTaskId(e.getId()));
                limiter.releaseSession(userId);
                log.info("kb upload complete userId={} uploadId={} fileId={} size={}",
                        userId, e.getId(), file.getId(), e.getTotalBytes());
                return toFileResponse(file);
            } catch (RuntimeException ex) {
                uploadMapper.casStatus(e.getId(),
                        KbFileUploadEntity.STATUS_COMPLETING, KbFileUploadEntity.STATUS_PENDING);
                throw ex;
            }
        }
    }

    public void abort(Long uploadId) {
        Long userId = SecurityUtils.requireCurrentUserId();
        KbFileUploadEntity e = requireOwned(uploadId, userId);
        if (KbFileUploadEntity.STATUS_COMPLETED.equals(e.getStatus())) {
            throw new BusinessException(409, "已完成的上传不能取消");
        }
        if (KbFileUploadEntity.STATUS_COMPLETING.equals(e.getStatus())) {
            throw new BusinessException(409, "文件正在合并，无法取消");
        }
        e.setStatus(KbFileUploadEntity.STATUS_ABORTED);
        uploadMapper.updateById(e);
        scratchWorkspace.cleanupScratch(SCRATCH_MODULE, scratchTaskId(e.getId()));
        uploadMapper.deleteById(e.getId());
        limiter.releaseSession(userId);
    }

    /** 清理过期未完成会话（定时任务）。 */
    public int cleanupExpired() {
        LocalDateTime now = LocalDateTime.now();
        List<KbFileUploadEntity> expired = uploadMapper.selectList(
                new LambdaQueryWrapper<KbFileUploadEntity>()
                        .in(KbFileUploadEntity::getStatus,
                                KbFileUploadEntity.STATUS_PENDING,
                                KbFileUploadEntity.STATUS_COMPLETING,
                                KbFileUploadEntity.STATUS_ABORTED,
                                KbFileUploadEntity.STATUS_COMPLETED)
                        .lt(KbFileUploadEntity::getExpiresAt, now)
                        .last("LIMIT 100"));
        int n = 0;
        for (KbFileUploadEntity e : expired) {
            try {
                scratchWorkspace.cleanupScratch(SCRATCH_MODULE, scratchTaskId(e.getId()));
                Long uid = e.getUserId();
                uploadMapper.deleteById(e.getId());
                if (uid != null && !KbFileUploadEntity.STATUS_COMPLETED.equals(e.getStatus())) {
                    limiter.releaseSession(uid);
                }
                n++;
            } catch (Exception ex) {
                log.warn("清理过期上传失败 uploadId={}: {}", e.getId(), ex.getMessage());
            }
        }
        return n;
    }

    static String scratchTaskId(Long uploadId) {
        return "up" + uploadId;
    }

    static String partFileName(int partNumber) {
        return "part-" + partNumber;
    }

    private static boolean isCompletePart(Path dest, long expected) {
        try {
            return Files.isRegularFile(dest) && Files.size(dest) == expected;
        } catch (IOException e) {
            return false;
        }
    }

    static long expectedPartSize(KbFileUploadEntity e, int partNumber) {
        long total = e.getTotalBytes();
        int chunk = e.getChunkSize();
        int parts = e.getTotalParts();
        if (partNumber < parts) {
            return chunk;
        }
        long last = total - (long) chunk * (parts - 1);
        return last > 0 ? last : chunk;
    }

    private List<Path> requireAllParts(KbFileUploadEntity e) {
        Path dir = scratchWorkspace.resolveTaskScratch(SCRATCH_MODULE, scratchTaskId(e.getId()));
        List<Path> parts = new ArrayList<>(e.getTotalParts());
        for (int i = 1; i <= e.getTotalParts(); i++) {
            Path p = dir.resolve(partFileName(i));
            if (!Files.isRegularFile(p)) {
                throw new BusinessException(400, "缺少分片 " + i);
            }
            try {
                long sz = Files.size(p);
                long expected = expectedPartSize(e, i);
                if (sz != expected) {
                    throw new BusinessException(400, "分片 " + i + " 大小不匹配");
                }
            } catch (IOException ex) {
                throw new BusinessException(500, "读取分片失败: " + ex.getMessage());
            }
            parts.add(p);
        }
        return parts;
    }

    private List<Integer> listReceivedParts(KbFileUploadEntity e) {
        Path dir = scratchWorkspace.resolveTaskScratch(SCRATCH_MODULE, scratchTaskId(e.getId()));
        List<Integer> received = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return received;
        }
        for (int i = 1; i <= e.getTotalParts(); i++) {
            Path p = dir.resolve(partFileName(i));
            try {
                if (Files.isRegularFile(p) && Files.size(p) == expectedPartSize(e, i)) {
                    received.add(i);
                }
            } catch (IOException ignored) {
                // skip
            }
        }
        return received;
    }

    private long uploadedBytes(KbFileUploadEntity e, List<Integer> received) {
        long n = 0;
        for (Integer p : received) {
            n += expectedPartSize(e, p);
        }
        return n;
    }

    private void copyExactly(InputStream in, Path dest, long expected) throws IOException {
        try (OutputStream out = Files.newOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            long copied = 0;
            while (true) {
                int r = in.read(buf);
                if (r < 0) {
                    break;
                }
                copied += r;
                if (copied > expected) {
                    throw new BusinessException(400, "分片超出声明大小");
                }
                out.write(buf, 0, r);
            }
            if (copied != expected) {
                throw new BusinessException(400, "分片不完整，期望 " + expected + " 实际 " + copied);
            }
        }
    }

    private FileUploadSessionResponse toSession(KbFileUploadEntity e) {
        List<Integer> received = listReceivedParts(e);
        return FileUploadSessionResponse.builder()
                .uploadId(e.getId())
                .noteId(e.getNoteId())
                .originalName(e.getOriginalName())
                .contentType(e.getContentType())
                .kind(e.getKind())
                .totalBytes(e.getTotalBytes() == null ? 0 : e.getTotalBytes())
                .chunkSize(e.getChunkSize() == null ? 0 : e.getChunkSize())
                .totalParts(e.getTotalParts() == null ? 0 : e.getTotalParts())
                .status(e.getStatus())
                .receivedParts(received)
                .uploadedBytes(uploadedBytes(e, received))
                .expiresAt(e.getExpiresAt())
                .build();
    }

    private FileResponse toFileResponse(KbFileEntity e) {
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

    private KbFileUploadEntity requireOwned(Long id, Long userId) {
        KbFileUploadEntity e = uploadMapper.selectById(id);
        if (e == null || !Objects.equals(e.getUserId(), userId)) {
            throw new BusinessException(404, "上传会话不存在");
        }
        if (e.getExpiresAt() != null && e.getExpiresAt().isBefore(LocalDateTime.now())
                && KbFileUploadEntity.STATUS_PENDING.equals(e.getStatus())) {
            throw new BusinessException(410, "上传会话已过期");
        }
        return e;
    }

    private void requireNoteOwned(Long noteId, Long userId) {
        KbNoteEntity n = noteMapper.selectById(noteId);
        if (n == null || !Objects.equals(n.getUserId(), userId)) {
            throw new BusinessException(404, "笔记不存在");
        }
    }
}
