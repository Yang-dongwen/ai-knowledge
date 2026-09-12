package com.dwcode.okxbot.rag.adapter.vector;

import com.dwcode.okxbot.rag.port.VectorFilter;
import com.dwcode.okxbot.rag.port.VectorHit;
import com.dwcode.okxbot.rag.port.VectorPoint;
import com.dwcode.okxbot.rag.port.VectorStorePort;

import java.util.List;

public class NoopVectorStoreAdapter implements VectorStorePort {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public void ensureCollection(int dimensions) {
        // no-op
    }

    @Override
    public void upsert(List<VectorPoint> points) {
        // no-op
    }

    @Override
    public void delete(VectorFilter filter) {
        // no-op
    }

    @Override
    public List<VectorHit> search(long userId, float[] queryVector, int topK, double scoreThreshold) {
        return List.of();
    }
}
