package com.dwcode.okxbot.rag.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.kb.config.KbProperties;
import com.dwcode.okxbot.kb.entity.KbNoteEntity;
import com.dwcode.okxbot.kb.mapper.KbNoteMapper;
import com.dwcode.okxbot.rag.config.RagAvailability;
import com.dwcode.okxbot.rag.port.EmbeddingPort;
import com.dwcode.okxbot.rag.port.VectorHit;
import com.dwcode.okxbot.rag.port.VectorStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class HybridSearchService {

    private final RagAvailability availability;
    private final EmbeddingPort embeddingPort;
    private final VectorStorePort vectorStorePort;
    private final KbNoteMapper noteMapper;
    private final KbProperties kbProperties;

    public boolean live() {
        return availability.live();
    }

    public List<HybridHit> search(long userId, String query, int topK) {
        if (userId <= 0 || !StringUtils.hasText(query)) {
            return List.of();
        }
        int k = Math.max(1, Math.min(20, topK));
        List<HybridHit> keywordHits = keywordHits(userId, query, k);
        List<HybridHit> vectorHits = List.of();
        if (availability.live()) {
            try {
                float[] qv = embeddingPort.embedQuery(query.trim());
                List<VectorHit> raw = vectorStorePort.search(
                        userId, qv, k, kbProperties.getRag().getScoreThreshold());
                vectorHits = raw.stream()
                        .filter(h -> h.getUserId() == userId)
                        .map(this::fromVector)
                        .toList();
            } catch (Exception e) {
                log.warn("向量检索失败，降级 LIKE: {}", e.getMessage());
            }
        }
        if (vectorHits.isEmpty()) {
            return keywordHits;
        }
        if (keywordHits.isEmpty()) {
            return vectorHits.size() > k ? vectorHits.subList(0, k) : vectorHits;
        }
        return rrf(keywordHits, vectorHits, k, kbProperties.getRag().getRrfK());
    }

    private HybridHit fromVector(VectorHit h) {
        String snippet = h.getText();
        if (snippet != null && snippet.length() > 240) {
            snippet = snippet.substring(0, 240) + "…";
        }
        return HybridHit.builder()
                .noteId(h.getNoteId())
                .fileId(h.getFileId())
                .sourceType(h.getSourceType())
                .title(h.getTitle())
                .snippet(snippet)
                .fileName(h.getFileName())
                .kind(h.getKind())
                .score(h.getScore())
                .build();
    }

    List<HybridHit> keywordHits(long userId, String query, int limit) {
        String trimmed = query.trim();
        final String kw = trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
        List<KbNoteEntity> notes = noteMapper.selectList(new LambdaQueryWrapper<KbNoteEntity>()
                .eq(KbNoteEntity::getUserId, userId)
                .eq(KbNoteEntity::getIsDeleted, 0)
                .select(KbNoteEntity::getId, KbNoteEntity::getTitle, KbNoteEntity::getSnippet,
                        KbNoteEntity::getContentText, KbNoteEntity::getUpdatedAt)
                .and(w -> w.like(KbNoteEntity::getTitle, kw)
                        .or().like(KbNoteEntity::getContentText, kw)
                        .or().like(KbNoteEntity::getSnippet, kw))
                .orderByDesc(KbNoteEntity::getUpdatedAt)
                .last("LIMIT " + Math.max(1, limit)));
        int radius = kbProperties.getSearch().getHighlightRadius();
        List<HybridHit> hits = new ArrayList<>();
        for (KbNoteEntity n : notes) {
            String snippet = com.dwcode.okxbot.kb.service.KbNoteService.buildMatchSnippet(
                    n.getTitle(), n.getContentText(), n.getSnippet(), kw, radius);
            hits.add(HybridHit.builder()
                    .noteId(n.getId())
                    .sourceType("note")
                    .title(n.getTitle())
                    .snippet(snippet)
                    .score(0)
                    .build());
        }
        return hits;
    }

    static List<HybridHit> rrf(List<HybridHit> a, List<HybridHit> b, int topK, int rrfK) {
        int k = Math.max(1, rrfK);
        Map<String, Acc> acc = new HashMap<>();
        addRank(acc, a, k);
        addRank(acc, b, k);
        return acc.values().stream()
                .sorted(Comparator.comparingDouble((Acc x) -> x.score).reversed())
                .limit(topK)
                .map(x -> x.hit)
                .toList();
    }

    private static void addRank(Map<String, Acc> acc, List<HybridHit> hits, int k) {
        for (int i = 0; i < hits.size(); i++) {
            HybridHit h = hits.get(i);
            String key = h.getSourceType() + ":" + h.getNoteId() + ":" + (h.getFileId() == null ? 0 : h.getFileId());
            double add = 1.0 / (k + i + 1);
            Acc cur = acc.get(key);
            if (cur == null) {
                acc.put(key, new Acc(h, add));
            } else {
                cur.score += add;
                if (h.getSnippet() != null && (cur.hit.getSnippet() == null || cur.hit.getSnippet().length() < h.getSnippet().length())) {
                    cur.hit = h;
                }
            }
        }
    }

    private static class Acc {
        HybridHit hit;
        double score;

        Acc(HybridHit hit, double score) {
            this.hit = hit;
            this.score = score;
        }
    }
}
