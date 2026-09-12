package com.dwcode.okxbot.rag.adapter.vector;

import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.rag.port.VectorFilter;
import com.dwcode.okxbot.rag.port.VectorHit;
import com.dwcode.okxbot.rag.port.VectorPoint;
import com.dwcode.okxbot.rag.port.VectorStorePort;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
import static io.qdrant.client.WithPayloadSelectorFactory.enable;

/**
 * Qdrant Cloud / 自建。所有读写强制 user_id。
 */
@Slf4j
public class QdrantVectorStoreAdapter implements VectorStorePort {

    private final AiProperties.VectorStoreConfig cfg;
    private final QdrantClient client;
    private volatile boolean collectionReady;

    public QdrantVectorStoreAdapter(AiProperties.VectorStoreConfig cfg) {
        this.cfg = cfg;
        QdrantGrpcClient.Builder b = QdrantGrpcClient.newBuilder(cfg.getHost().trim(), cfg.getPort(), cfg.isUseTls());
        if (StringUtils.hasText(cfg.getApiKey())) {
            b.withApiKey(cfg.getApiKey().trim());
        }
        this.client = new QdrantClient(b.build());
    }

    @Override
    public boolean available() {
        return StringUtils.hasText(cfg.getHost()) && StringUtils.hasText(cfg.getApiKey());
    }

    @Override
    public synchronized void ensureCollection(int dimensions) {
        if (collectionReady) {
            return;
        }
        if (dimensions <= 0) {
            throw new BusinessException(500, "向量维度非法");
        }
        String name = cfg.getCollection();
        try {
            Boolean exists = client.collectionExistsAsync(name).get();
            if (Boolean.TRUE.equals(exists)) {
                collectionReady = true;
                return;
            }
            Distance distance = "dot".equalsIgnoreCase(cfg.getDistance())
                    ? Distance.Dot
                    : ("euclid".equalsIgnoreCase(cfg.getDistance()) ? Distance.Euclid : Distance.Cosine);
            client.createCollectionAsync(
                    name,
                    VectorParams.newBuilder().setSize(dimensions).setDistance(distance).build()
            ).get();
            collectionReady = true;
            log.info("Qdrant collection 已创建 name={} dim={}", name, dimensions);
        } catch (Exception e) {
            throw new BusinessException(502, "Qdrant collection 初始化失败: " + e.getMessage());
        }
    }

    @Override
    public void upsert(List<VectorPoint> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        List<Points.PointStruct> structs = new ArrayList<>(points.size());
        for (VectorPoint p : points) {
            requireUser(p.getUserId());
            Map<String, JsonWithInt.Value> payload = new HashMap<>();
            payload.put("user_id", value(String.valueOf(p.getUserId())));
            payload.put("source_type", value(p.getSourceType()));
            payload.put("note_id", value(String.valueOf(p.getNoteId())));
            payload.put("file_id", value(p.getFileId() == null ? "0" : String.valueOf(p.getFileId())));
            payload.put("chunk_index", value(p.getChunkIndex()));
            payload.put("title", value(nullToEmpty(p.getTitle())));
            payload.put("text", value(nullToEmpty(p.getText())));
            payload.put("kind", value(nullToEmpty(p.getKind())));
            payload.put("file_name", value(nullToEmpty(p.getFileName())));
            structs.add(Points.PointStruct.newBuilder()
                    .setId(id(UUID.fromString(p.getId())))
                    .setVectors(vectors(toList(p.getVector())))
                    .putAllPayload(payload)
                    .build());
        }
        try {
            client.upsertAsync(cfg.getCollection(), structs).get();
        } catch (Exception e) {
            throw new BusinessException(502, "Qdrant upsert 失败: " + e.getMessage());
        }
    }

    @Override
    public void delete(VectorFilter filter) {
        if (filter == null) {
            throw new BusinessException(400, "向量删除必须带过滤条件");
        }
        requireUser(filter.getUserId());
        Points.Filter.Builder fb = Points.Filter.newBuilder()
                .addMust(matchKeyword("user_id", String.valueOf(filter.getUserId())));
        if (StringUtils.hasText(filter.getSourceType())) {
            fb.addMust(matchKeyword("source_type", filter.getSourceType()));
        }
        if (filter.getNoteId() != null) {
            fb.addMust(matchKeyword("note_id", String.valueOf(filter.getNoteId())));
        }
        if (filter.getFileId() != null) {
            fb.addMust(matchKeyword("file_id", String.valueOf(filter.getFileId())));
        }
        try {
            client.deleteAsync(cfg.getCollection(), fb.build()).get();
        } catch (Exception e) {
            throw new BusinessException(502, "Qdrant delete 失败: " + e.getMessage());
        }
    }

    @Override
    public List<VectorHit> search(long userId, float[] queryVector, int topK, double scoreThreshold) {
        requireUser(userId);
        if (queryVector == null || queryVector.length == 0) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(50, topK));
        Points.Filter filter = Points.Filter.newBuilder()
                .addMust(matchKeyword("user_id", String.valueOf(userId)))
                .build();
        try {
            List<Points.ScoredPoint> scored = client.searchAsync(Points.SearchPoints.newBuilder()
                    .setCollectionName(cfg.getCollection())
                    .addAllVector(toList(queryVector))
                    .setLimit(limit)
                    .setFilter(filter)
                    .setWithPayload(enable(true))
                    .setScoreThreshold((float) Math.max(0, scoreThreshold))
                    .build()).get();
            List<VectorHit> hits = new ArrayList<>();
            for (Points.ScoredPoint sp : scored) {
                Map<String, JsonWithInt.Value> pl = sp.getPayloadMap();
                long uid = parseLong(pl, "user_id", 0);
                if (uid != userId) {
                    continue;
                }
                hits.add(VectorHit.builder()
                        .pointId(sp.getId().getUuid())
                        .score(sp.getScore())
                        .userId(uid)
                        .sourceType(str(pl, "source_type"))
                        .noteId(parseLong(pl, "note_id", 0))
                        .fileId(parseLong(pl, "file_id", 0) == 0 ? null : parseLong(pl, "file_id", 0))
                        .chunkIndex((int) parseLong(pl, "chunk_index", 0))
                        .title(str(pl, "title"))
                        .text(str(pl, "text"))
                        .kind(str(pl, "kind"))
                        .fileName(str(pl, "file_name"))
                        .build());
            }
            return hits;
        } catch (Exception e) {
            throw new BusinessException(502, "Qdrant 搜索失败: " + e.getMessage());
        }
    }

    static Points.Filter buildUserFilter(long userId) {
        requireUser(userId);
        return Points.Filter.newBuilder()
                .addMust(matchKeyword("user_id", String.valueOf(userId)))
                .build();
    }

    private static void requireUser(long userId) {
        if (userId <= 0) {
            throw new BusinessException(400, "向量操作必须带 userId");
        }
    }

    private static List<Float> toList(float[] v) {
        List<Float> list = new ArrayList<>(v.length);
        for (float f : v) {
            list.add(f);
        }
        return list;
    }

    private static String str(Map<String, JsonWithInt.Value> pl, String key) {
        JsonWithInt.Value v = pl.get(key);
        return v == null ? "" : v.getStringValue();
    }

    private static long parseLong(Map<String, JsonWithInt.Value> pl, String key, long def) {
        JsonWithInt.Value v = pl.get(key);
        if (v == null) {
            return def;
        }
        if (v.hasIntegerValue()) {
            return v.getIntegerValue();
        }
        String s = v.getStringValue();
        if (!StringUtils.hasText(s)) {
            return def;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
