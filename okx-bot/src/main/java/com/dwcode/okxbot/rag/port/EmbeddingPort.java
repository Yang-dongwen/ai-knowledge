package com.dwcode.okxbot.rag.port;

import java.util.List;

/**
 * 文本向量化。实现禁止泄漏 Google SDK 类型。
 */
public interface EmbeddingPort {

    boolean available();

    int dimensions();

    String modelId();

    float[] embedQuery(String text);

    List<float[]> embedDocuments(List<String> texts);
}
