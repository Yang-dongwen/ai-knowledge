package com.dwcode.okxbot.rag.adapter.embedding;

import com.dwcode.okxbot.rag.port.EmbeddingPort;

import java.util.Collections;
import java.util.List;

public class NoopEmbeddingAdapter implements EmbeddingPort {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public int dimensions() {
        return 0;
    }

    @Override
    public String modelId() {
        return "noop";
    }

    @Override
    public float[] embedQuery(String text) {
        throw new IllegalStateException("embedding 未配置");
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        return Collections.emptyList();
    }
}
