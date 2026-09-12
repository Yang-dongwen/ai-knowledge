package com.dwcode.okxbot.rag.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class KbIndexOutboxService {

    private final KbIndexJobMapper mapper;

    public void enqueueNoteUpsert(Long userId, Long noteId) {
        enqueue(userId, KbIndexJobEntity.TYPE_NOTE, noteId, KbIndexJobEntity.OP_UPSERT);
    }

    public void enqueueNoteDelete(Long userId, Long noteId) {
        enqueue(userId, KbIndexJobEntity.TYPE_NOTE, noteId, KbIndexJobEntity.OP_DELETE);
    }

    public void enqueueFileUpsert(Long userId, Long fileId) {
        enqueue(userId, KbIndexJobEntity.TYPE_FILE, fileId, KbIndexJobEntity.OP_UPSERT);
    }

    public void enqueueFileDelete(Long userId, Long fileId) {
        enqueue(userId, KbIndexJobEntity.TYPE_FILE, fileId, KbIndexJobEntity.OP_DELETE);
    }

    public int enqueueRebuildForUser(Long userId) {
        if (userId == null) {
            return 0;
        }
        // worker 会按 NOTE/FILE 拉全量：这里只把用户已有 PENDING 以外的源交由 rebuild 接口另行插入
        return 0;
    }

    public void enqueue(Long userId, String sourceType, Long sourceId, String op) {
        if (userId == null || sourceId == null || sourceType == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        KbIndexJobEntity pending = mapper.selectOne(new LambdaQueryWrapper<KbIndexJobEntity>()
                .eq(KbIndexJobEntity::getUserId, userId)
                .eq(KbIndexJobEntity::getSourceType, sourceType)
                .eq(KbIndexJobEntity::getSourceId, sourceId)
                .eq(KbIndexJobEntity::getStatus, KbIndexJobEntity.STATUS_PENDING)
                .last("LIMIT 1"));
        if (pending != null) {
            pending.setOp(op);
            pending.setNextRunAt(now);
            pending.setUpdatedAt(now);
            mapper.updateById(pending);
            return;
        }
        KbIndexJobEntity e = new KbIndexJobEntity();
        e.setUserId(userId);
        e.setSourceType(sourceType);
        e.setSourceId(sourceId);
        e.setOp(op);
        e.setStatus(KbIndexJobEntity.STATUS_PENDING);
        e.setAttempts(0);
        e.setNextRunAt(now);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        mapper.insert(e);
    }

    public boolean casRunning(Long id) {
        return mapper.update(null, new LambdaUpdateWrapper<KbIndexJobEntity>()
                .eq(KbIndexJobEntity::getId, id)
                .eq(KbIndexJobEntity::getStatus, KbIndexJobEntity.STATUS_PENDING)
                .set(KbIndexJobEntity::getStatus, KbIndexJobEntity.STATUS_RUNNING)
                .set(KbIndexJobEntity::getUpdatedAt, LocalDateTime.now())) == 1;
    }
}
