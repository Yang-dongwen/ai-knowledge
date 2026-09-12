package com.dwcode.okxbot.rag.port;

import java.util.List;

/**
 * 向量库。search / delete 必须带 userId。
 */
public interface VectorStorePort {

    boolean available();

    void ensureCollection(int dimensions);

    void upsert(List<VectorPoint> points);

    void delete(VectorFilter filter);

    List<VectorHit> search(long userId, float[] queryVector, int topK, double scoreThreshold);
}
