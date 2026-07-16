package com.dwcode.okxbot.aigen.service;

import com.dwcode.okxbot.aigen.dto.AigenTemplateResponse;
import com.dwcode.okxbot.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模板白名单与 composition 映射。
 */
@Component
public class TemplateRegistry {

    public static final String KNOWLEDGE_CARDS = "knowledge-cards";
    public static final String INSIGHT_COMPARE = "insight-compare";
    /** Visual Timeline 画面优先模式（非口播模板） */
    public static final String VISUAL_TIMELINE = "visual-timeline";

    private static final Map<String, TemplateDef> DEFS = new LinkedHashMap<>();

    static {
        DEFS.put(VISUAL_TIMELINE, new TemplateDef(
                VISUAL_TIMELINE,
                "画面短片 (Visual)",
                "镜头表 + AI 画面 + 叠字合成（默认无强制口播）",
                "VisualTimeline",
                Set.of("visual"),
                List.of("9:16", "16:9", "1:1"),
                40, 10, 90
        ));
        DEFS.put(KNOWLEDGE_CARDS, new TemplateDef(
                KNOWLEDGE_CARDS,
                "知识卡片",
                "竖屏知识科普：标题 + 要点 + 结尾",
                "KnowledgeCards",
                Set.of("title", "bullets", "outro"),
                List.of("9:16", "16:9", "1:1"),
                30, 10, 90
        ));
        DEFS.put(INSIGHT_COMPARE, new TemplateDef(
                INSIGHT_COMPARE,
                "洞察对比",
                "钩子提问 → 左右对比 → 数字/洞察 → 收尾（独立版式）",
                "InsightCompare",
                Set.of("hook", "compare", "insight", "metric", "outro"),
                List.of("9:16", "16:9"),
                30, 15, 90
        ));
        // 以下仍复用 KnowledgeCards Composition（换皮模板，后续可拆真模板）
        DEFS.put("product-pitch", new TemplateDef(
                "product-pitch", "产品卖点", "卖点罗列与行动号召（版式同知识卡片）",
                "KnowledgeCards",
                Set.of("title", "bullets", "outro"),
                List.of("9:16", "16:9"),
                30, 15, 90
        ));
        DEFS.put("talking-points", new TemplateDef(
                "talking-points", "口播字幕", "强字幕口播条（版式同知识卡片）",
                "KnowledgeCards",
                Set.of("title", "bullets", "outro"),
                List.of("9:16"),
                45, 15, 90
        ));
        DEFS.put("tech-promo", new TemplateDef(
                "tech-promo", "科技宣传", "短宣传片风格（版式同知识卡片）",
                "KnowledgeCards",
                Set.of("title", "bullets", "outro"),
                List.of("16:9", "9:16"),
                20, 10, 90
        ));
    }

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
