package com.dwcode.okxbot.aigen.service;

import com.dwcode.okxbot.aigen.dto.AigenTemplateResponse;
import com.dwcode.okxbot.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模板白名单与 composition 映射。
 */
@Component
public class TemplateRegistry {

    public static final String KNOWLEDGE_CARDS = "knowledge-cards";

    private static final Map<String, TemplateDef> DEFS = Map.of(
            KNOWLEDGE_CARDS, new TemplateDef(
                    KNOWLEDGE_CARDS,
                    "知识卡片",
                    "竖屏知识科普：标题 + 要点 + 结尾",
                    "KnowledgeCards",
                    Set.of("title", "bullets", "outro"),
                    List.of("9:16", "16:9", "1:1"),
                    30, 10, 90
            ),
            "product-pitch", new TemplateDef(
                    "product-pitch", "产品卖点", "卖点罗列与行动号召",
                    "KnowledgeCards", // Phase 1 复用同一 Composition
                    Set.of("title", "bullets", "outro"),
                    List.of("9:16", "16:9"),
                    30, 15, 90
            ),
            "talking-points", new TemplateDef(
                    "talking-points", "口播字幕", "强字幕口播条",
                    "KnowledgeCards",
                    Set.of("title", "bullets", "outro"),
                    List.of("9:16"),
                    45, 15, 90
            ),
            "tech-promo", new TemplateDef(
                    "tech-promo", "科技宣传", "短宣传片风格",
                    "KnowledgeCards",
                    Set.of("title", "bullets", "outro"),
                    List.of("16:9", "9:16"),
                    20, 10, 90
            )
    );

    public TemplateDef require(String templateId) {
        TemplateDef def = DEFS.get(templateId);
        if (def == null) {
            throw new BusinessException(400, "未知模板: " + templateId);
        }
        return def;
    }

    public boolean exists(String templateId) {
        return DEFS.containsKey(templateId);
    }

    public List<AigenTemplateResponse> listAll() {
        return DEFS.values().stream()
                .map(d -> AigenTemplateResponse.builder()
                        .id(d.id())
                        .name(d.name())
                        .description(d.description())
                        .compositionId(d.compositionId())
                        .aspectRatios(d.aspectRatios())
                        .defaultDurationSec(d.defaultDurationSec())
                        .minDurationSec(d.minDurationSec())
                        .maxDurationSec(d.maxDurationSec())
                        .build())
                .toList();
    }

    public record TemplateDef(
            String id,
            String name,
            String description,
            String compositionId,
            Set<String> allowedSceneTypes,
            List<String> aspectRatios,
            int defaultDurationSec,
            int minDurationSec,
            int maxDurationSec
    ) {}
}
