package com.dwcode.okxbot.rag.config;

import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.rag.adapter.embedding.GoogleAiStudioEmbeddingAdapter;
import com.dwcode.okxbot.rag.adapter.embedding.NoopEmbeddingAdapter;
import com.dwcode.okxbot.rag.adapter.vector.NoopVectorStoreAdapter;
import com.dwcode.okxbot.rag.adapter.vector.QdrantVectorStoreAdapter;
import com.dwcode.okxbot.rag.port.EmbeddingPort;
import com.dwcode.okxbot.rag.port.VectorStorePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class RagBeanConfig {

    @Bean
    public EmbeddingPort embeddingPort(AiProperties aiProperties, RagAvailability availability) {
        if (availability.embeddingConfigured()) {
            log.info("EmbeddingPort=GoogleAiStudio model={}", aiProperties.getEmbedding().getModel());
            return new GoogleAiStudioEmbeddingAdapter(aiProperties.getEmbedding());
        }
        log.info("EmbeddingPort=Noop（未配置 ai.embedding.api-key）");
        return new NoopEmbeddingAdapter();
    }

    @Bean
    public VectorStorePort vectorStorePort(AiProperties aiProperties, RagAvailability availability) {
        if (availability.vectorConfigured()) {
            log.info("VectorStorePort=Qdrant host={} collection={}",
                    aiProperties.getVectorStore().getHost(),
                    aiProperties.getVectorStore().getCollection());
            return new QdrantVectorStoreAdapter(aiProperties.getVectorStore());
        }
        log.info("VectorStorePort=Noop（未配置 ai.vector-store.host/api-key）");
        return new NoopVectorStoreAdapter();
    }
}
