package com.dwcode.okxbot.rag.config;

import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.kb.config.KbProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RagConfigValidator {

    private final KbProperties kbProperties;
    private final AiProperties aiProperties;
    private final RagAvailability availability;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        if (!kbProperties.getRag().isEnabled()) {
            log.info("知识库 RAG 关闭（kb.rag.enabled=false），搜索走 LIKE");
            return;
        }
        if (!availability.live()) {
            log.warn("知识库 RAG 已启用但 embedding/qdrant 密钥或 host 未配齐，检索降级为 LIKE。"
                    + " 填写 ai.embedding.api-key 与 ai.vector-store.host/api-key 后生效");
            return;
        }
        log.info("知识库 RAG 已启用 embedding.model={} dim={} qdrant.host={} collection={}",
                aiProperties.getEmbedding().getModel(),
                aiProperties.getEmbedding().getOutputDimensionality(),
                aiProperties.getVectorStore().getHost(),
                aiProperties.getVectorStore().getCollection());
    }
}
