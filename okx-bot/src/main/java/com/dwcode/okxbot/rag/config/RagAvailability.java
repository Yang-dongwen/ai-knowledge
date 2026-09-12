package com.dwcode.okxbot.rag.config;

import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.kb.config.KbProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class RagAvailability {

    private final KbProperties kbProperties;
    private final AiProperties aiProperties;

    public boolean enabled() {
        return kbProperties.getRag().isEnabled();
    }

    public boolean embeddingConfigured() {
        AiProperties.EmbeddingConfig e = aiProperties.getEmbedding();
        return e != null && StringUtils.hasText(e.getApiKey());
    }

    public boolean vectorConfigured() {
        AiProperties.VectorStoreConfig v = aiProperties.getVectorStore();
        return v != null && StringUtils.hasText(v.getHost()) && StringUtils.hasText(v.getApiKey());
    }

    /** 向量检索可真正跑（否则降级 LIKE） */
    public boolean live() {
        return enabled() && embeddingConfigured() && vectorConfigured();
    }

    public boolean hybridSearch() {
        String mode = kbProperties.getSearch().getMode();
        return mode == null || mode.isBlank() || "hybrid".equalsIgnoreCase(mode.trim());
    }
}
