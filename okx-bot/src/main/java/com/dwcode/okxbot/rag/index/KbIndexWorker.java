package com.dwcode.okxbot.rag.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.kb.config.KbProperties;
import com.dwcode.okxbot.kb.entity.KbFileEntity;
import com.dwcode.okxbot.kb.entity.KbNoteEntity;
import com.dwcode.okxbot.kb.mapper.KbFileMapper;
import com.dwcode.okxbot.kb.mapper.KbNoteMapper;
import com.dwcode.okxbot.rag.adapter.extract.TextExtractRouter;
import com.dwcode.okxbot.rag.chunk.CharOverlapChunker;
import com.dwcode.okxbot.rag.config.RagAvailability;
import com.dwcode.okxbot.rag.port.EmbeddingPort;
import com.dwcode.okxbot.rag.port.TextChunk;
import com.dwcode.okxbot.rag.port.VectorFilter;
import com.dwcode.okxbot.rag.port.VectorPoint;
import com.dwcode.okxbot.rag.port.VectorStorePort;
import com.dwcode.okxbot.storage.ObjectStoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class KbIndexWorker {

    private final KbIndexJobMapper jobMapper;
    private final KbIndexOutboxService outboxService;
    private final KbNoteMapper noteMapper;
    private final KbFileMapper fileMapper;
    private final ObjectStoragePort objectStorage;
    private final TextExtractRouter extractRouter;
    private final CharOverlapChunker chunker;
    private final EmbeddingPort embeddingPort;
    private final VectorStorePort vectorStorePort;
    private final RagAvailability availability;
    private final KbProperties kbProperties;
    private final AiProperties aiProperties;

    @Scheduled(cron = "${kb.rag.worker-cron:0/20 * * * * ?}")
    public void tick() {
        if (!availability.live()) {
            return;
        }
        int batch = Math.max(1, kbProperties.getRag().getIndexBatchSize());
        List<KbIndexJobEntity> jobs = jobMapper.selectList(new LambdaQueryWrapper<KbIndexJobEntity>()
                .eq(KbIndexJobEntity::getStatus, KbIndexJobEntity.STATUS_PENDING)
                .le(KbIndexJobEntity::getNextRunAt, LocalDateTime.now())
                .orderByAsc(KbIndexJobEntity::getId)
                .last("LIMIT " + batch));
        if (jobs.isEmpty()) {
            return;
        }
        try {
            vectorStorePort.ensureCollection(embeddingPort.dimensions());
        } catch (Exception e) {
            log.warn("Qdrant ensureCollection 失败，本轮跳过: {}", e.getMessage());
            return;
        }
        for (KbIndexJobEntity job : jobs) {
            if (!outboxService.casRunning(job.getId())) {
                continue;
            }
            process(job);
        }
    }

    public int rebuildUser(Long userId) {
        if (userId == null) {
            return 0;
        }
        int n = 0;
        List<KbNoteEntity> notes = noteMapper.selectList(new LambdaQueryWrapper<KbNoteEntity>()
                .eq(KbNoteEntity::getUserId, userId)
                .eq(KbNoteEntity::getIsDeleted, 0)
                .select(KbNoteEntity::getId, KbNoteEntity::getUserId));
        for (KbNoteEntity note : notes) {
            outboxService.enqueueNoteUpsert(userId, note.getId());
            n++;
        }
        List<KbFileEntity> files = fileMapper.selectList(new LambdaQueryWrapper<KbFileEntity>()
                .eq(KbFileEntity::getUserId, userId)
                .select(KbFileEntity::getId, KbFileEntity::getUserId));
        for (KbFileEntity file : files) {
            outboxService.enqueueFileUpsert(userId, file.getId());
            n++;
        }
        return n;
    }

    private void process(KbIndexJobEntity job) {
        try {
            if (KbIndexJobEntity.OP_DELETE.equals(job.getOp())) {
                deleteVectors(job);
            } else if (KbIndexJobEntity.TYPE_NOTE.equals(job.getSourceType())) {
                upsertNote(job);
            } else {
                upsertFile(job);
            }
            job.setStatus(KbIndexJobEntity.STATUS_DONE);
            job.setLastError(null);
            job.setUpdatedAt(LocalDateTime.now());
            jobMapper.updateById(job);
        } catch (Exception e) {
            int attempts = job.getAttempts() == null ? 1 : job.getAttempts() + 1;
            job.setAttempts(attempts);
            job.setLastError(truncate(e.getMessage(), 500));
            int max = Math.max(1, kbProperties.getRag().getMaxAttempts());
            if (attempts >= max) {
                job.setStatus(KbIndexJobEntity.STATUS_FAIL);
            } else {
                job.setStatus(KbIndexJobEntity.STATUS_PENDING);
                job.setNextRunAt(LocalDateTime.now().plusSeconds((long) Math.min(3600, Math.pow(2, attempts) * 15)));
            }
            job.setUpdatedAt(LocalDateTime.now());
            jobMapper.updateById(job);
            log.warn("kb index job 失败 id={} type={} source={} err={}",
                    job.getId(), job.getSourceType(), job.getSourceId(), e.getMessage());
        }
    }

    private void deleteVectors(KbIndexJobEntity job) {
        if (KbIndexJobEntity.TYPE_FILE.equals(job.getSourceType())) {
            vectorStorePort.delete(VectorFilter.builder()
                    .userId(job.getUserId())
                    .sourceType("file")
                    .fileId(job.getSourceId())
                    .build());
        } else {
            vectorStorePort.delete(VectorFilter.builder()
                    .userId(job.getUserId())
                    .noteId(job.getSourceId())
                    .build());
        }
    }

    private void upsertNote(KbIndexJobEntity job) {
        KbNoteEntity note = noteMapper.selectById(job.getSourceId());
        if (note == null || !job.getUserId().equals(note.getUserId()) || Integer.valueOf(1).equals(note.getIsDeleted())) {
            vectorStorePort.delete(VectorFilter.builder()
                    .userId(job.getUserId())
                    .noteId(job.getSourceId())
                    .build());
            return;
        }
        String title = note.getTitle() == null ? "" : note.getTitle();
        String body = StringUtils.hasText(note.getContentText()) ? note.getContentText() : "";
        String raw = (title + "\n" + body).trim();
        String hash = sha256(raw);
        if (hash.equals(job.getContentHash())) {
            return;
        }
        vectorStorePort.delete(VectorFilter.builder()
                .userId(job.getUserId())
                .sourceType("note")
                .noteId(note.getId())
                .build());
        writeChunks(job.getUserId(), "note", note.getId(), null, title, null, "note", raw);
        job.setContentHash(hash);
    }

    private void upsertFile(KbIndexJobEntity job) {
        KbFileEntity file = fileMapper.selectById(job.getSourceId());
        if (file == null || !job.getUserId().equals(file.getUserId())) {
            vectorStorePort.delete(VectorFilter.builder()
                    .userId(job.getUserId())
                    .sourceType("file")
                    .fileId(job.getSourceId())
                    .build());
            return;
        }
        String extracted = extractFile(file);
        String title = file.getOriginalName() == null ? "" : file.getOriginalName();
        String raw = (title + "\n" + extracted).trim();
        if (!StringUtils.hasText(raw)) {
            raw = title;
        }
        String hash = sha256(raw + "|" + file.getSizeBytes());
        if (hash.equals(job.getContentHash())) {
            return;
        }
        vectorStorePort.delete(VectorFilter.builder()
                .userId(job.getUserId())
                .sourceType("file")
                .fileId(file.getId())
                .build());
        writeChunks(job.getUserId(), "file", file.getNoteId() == null ? 0L : file.getNoteId(),
                file.getId(), title, file.getOriginalName(), file.getKind(), raw);
        job.setContentHash(hash);
    }

    private String extractFile(KbFileEntity file) {
        String kind = file.getKind() == null ? "other" : file.getKind().toLowerCase(Locale.ROOT);
        List<String> allow = kbProperties.getRag().getExtractKinds();
        if (allow != null && !allow.isEmpty() && !allow.contains(kind)) {
            return file.getOriginalName() == null ? "" : file.getOriginalName();
        }
        if (!StringUtils.hasText(file.getObjectKey()) || "pending".equals(file.getObjectKey())) {
            return file.getOriginalName() == null ? "" : file.getOriginalName();
        }
        long max = kbProperties.getRag().getMaxFileExtractBytes();
        if (file.getSizeBytes() != null && file.getSizeBytes() > max) {
            return file.getOriginalName() == null ? "" : file.getOriginalName();
        }
        try (InputStream in = objectStorage.openStream(file.getObjectKey())) {
            String text = extractRouter.extract(kind, file.getOriginalName(), file.getContentType(), in, max);
            return text == null ? "" : text;
        } catch (Exception e) {
            log.warn("附件抽文本失败 fileId={}: {}", file.getId(), e.getMessage());
            return file.getOriginalName() == null ? "" : file.getOriginalName();
        }
    }

    private void writeChunks(long userId, String sourceType, long noteId, Long fileId,
                             String title, String fileName, String kind, String raw) {
        KbProperties.Rag rag = kbProperties.getRag();
        List<TextChunk> chunks = chunker.chunk(raw, rag.getChunkSizeChars(), rag.getChunkOverlapChars());
        if (chunks.isEmpty()) {
            return;
        }
        List<String> texts = chunks.stream().map(TextChunk::getText).toList();
        List<float[]> vectors = embeddingPort.embedDocuments(texts);
        String model = embeddingPort.modelId();
        int dim = embeddingPort.dimensions();
        List<VectorPoint> points = new ArrayList<>();
        for (int i = 0; i < chunks.size() && i < vectors.size(); i++) {
            TextChunk c = chunks.get(i);
            String pid = UUID.nameUUIDFromBytes(
                    ("u:" + userId + "|n:" + noteId + "|f:" + (fileId == null ? 0 : fileId)
                            + "|i:" + c.getIndex() + "|m:" + model + "|d:" + dim)
                            .getBytes(StandardCharsets.UTF_8)
            ).toString();
            points.add(VectorPoint.builder()
                    .id(pid)
                    .vector(vectors.get(i))
                    .userId(userId)
                    .sourceType(sourceType)
                    .noteId(noteId)
                    .fileId(fileId)
                    .chunkIndex(c.getIndex())
                    .title(title)
                    .text(c.getText())
                    .kind(kind)
                    .fileName(fileName)
                    .build());
        }
        vectorStorePort.upsert(points);
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n);
    }
}
