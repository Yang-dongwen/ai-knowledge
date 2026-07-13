package com.dwcode.okxbot.aigen.service;

import com.dwcode.okxbot.aigen.domain.SceneDto;
import com.dwcode.okxbot.aigen.domain.SceneProps;
import com.dwcode.okxbot.aigen.domain.StoryboardDto;
import com.dwcode.okxbot.aigen.domain.StoryboardMeta;
import com.dwcode.okxbot.aigen.service.TemplateRegistry.TemplateDef;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Storyboard 本地校验（不信任 LLM）。
 */
@Service
public class StoryboardValidateService {

    private final TemplateRegistry templateRegistry;

    public StoryboardValidateService(TemplateRegistry templateRegistry) {
        this.templateRegistry = templateRegistry;
    }

    public List<String> validate(StoryboardDto sb, String expectedTemplateId, String aspectRatio, int targetDurationSec) {
        List<String> errors = new ArrayList<>();
        if (sb == null) {
            errors.add("storyboard 为空");
            return errors;
        }
        if (sb.getVersion() == null || !sb.getVersion().startsWith("1.")) {
            errors.add("version 必须为 1.x");
        }
        StoryboardMeta meta = sb.getMeta();
        if (meta == null) {
            errors.add("meta 缺失");
            return errors;
        }
        if (expectedTemplateId != null && meta.getTemplateId() != null
                && !expectedTemplateId.equals(meta.getTemplateId())) {
            errors.add("templateId 与任务不一致: " + meta.getTemplateId());
        }
        String tid = meta.getTemplateId() != null ? meta.getTemplateId() : expectedTemplateId;
        if (tid == null || !templateRegistry.exists(tid)) {
            errors.add("无效 templateId: " + tid);
            return errors;
        }
        TemplateDef def = templateRegistry.require(tid);

        if (sb.getScenes() == null || sb.getScenes().isEmpty()) {
            errors.add("scenes 不能为空");
            return errors;
        }
        if (sb.getScenes().size() > 12) {
            errors.add("scenes 最多 12 个，当前 " + sb.getScenes().size());
        }
        if (sb.getScenes().size() < 2) {
            errors.add("scenes 至少 2 个");
        }

        Set<String> ids = new HashSet<>();
        for (int i = 0; i < sb.getScenes().size(); i++) {
            SceneDto sc = sb.getScenes().get(i);
            String p = "scenes[" + i + "]";
            if (sc.getId() == null || sc.getId().isBlank()) {
                errors.add(p + ".id 不能为空");
            } else if (!ids.add(sc.getId())) {
                errors.add("重复 scene.id: " + sc.getId());
            }
            if (sc.getType() == null || !def.allowedSceneTypes().contains(sc.getType())) {
                errors.add(p + ".type 非法: " + sc.getType() + "，允许 " + def.allowedSceneTypes());
            }
            if (sc.getNarration() == null || sc.getNarration().isBlank()) {
                errors.add(p + ".narration 不能为空");
            } else if (sc.getNarration().length() > 500) {
                errors.add(p + ".narration 超过 500 字");
            }
            if (sc.getDurationInFrames() != null && sc.getDurationInFrames() < 1) {
                errors.add(p + ".durationInFrames 必须 > 0");
            }
            if (sc.getStartFrame() != null && sc.getStartFrame() < 0) {
                errors.add(p + ".startFrame 不能为负");
            }
            // 外链路径检查（规划阶段 props 不应有远程 URL）
            if (sc.getProps() != null && sc.getProps().getTitle() != null
                    && looksLikeUrl(sc.getProps().getTitle())) {
                errors.add(p + ".props 疑似包含 URL");
            }
            validateScenePropsByType(sc, p, errors);
        }

        if (TemplateRegistry.INSIGHT_COMPARE.equals(tid)) {
            validateInsightCompareStructure(sb, errors);
        }

        // audio.src 安全
        if (sb.getAudio() != null && sb.getAudio().getTracks() != null) {
            for (var t : sb.getAudio().getTracks()) {
                if (t.getSrc() != null && !t.getSrc().isBlank()) {
                    if (t.getSrc().contains("..") || looksLikeUrl(t.getSrc()) || t.getSrc().startsWith("/")) {
                        errors.add("非法 audio.src: " + t.getSrc());
                    }
                    if (!t.getSrc().startsWith("assets/")) {
                        errors.add("audio.src 必须位于 assets/ 下: " + t.getSrc());
                    }
                }
            }
        }

        int target = Math.max(5, Math.min(90, targetDurationSec));
        int fps = meta.getFps() != null && meta.getFps() > 0 ? meta.getFps() : 30;
        if (meta.getDurationInFrames() != null) {
            double sec = meta.getDurationInFrames() / (double) fps;
            if (sec > target * 1.5 + 5) {
                errors.add("总时长过长: ~" + (int) sec + "s，目标 " + target + "s");
            }
        }
        return errors;
    }

    private static void validateScenePropsByType(SceneDto sc, String p, List<String> errors) {
        SceneProps props = sc.getProps();
        if (props == null) {
            errors.add(p + ".props 不能为空");
            return;
        }
        String type = sc.getType();
        if (type == null) {
            return;
        }
        switch (type) {
            case "title", "hook" -> {
                if (blank(props.getTitle())) {
                    errors.add(p + ".props.title 不能为空");
                }
            }
            case "bullets", "insight" -> {
                if (blank(props.getHeading()) && blank(props.getTitle())) {
                    errors.add(p + ".props.heading 不能为空");
                }
                if (props.getItems() == null || props.getItems().isEmpty()) {
                    errors.add(p + ".props.items 至少 1 条");
                } else if (props.getItems().size() > 5) {
                    errors.add(p + ".props.items 建议 ≤5，当前 " + props.getItems().size());
                }
            }
            case "compare" -> {
                if (blank(props.getLeftLabel()) || blank(props.getRightLabel())) {
                    errors.add(p + ".props.leftLabel/rightLabel 不能为空");
                }
                int left = props.getLeftItems() == null ? 0 : props.getLeftItems().size();
                int right = props.getRightItems() == null ? 0 : props.getRightItems().size();
                if (left < 2 || right < 2) {
                    errors.add(p + ".props leftItems/rightItems 各至少 2 条");
                }
                if (left > 4 || right > 4) {
                    errors.add(p + ".props leftItems/rightItems 各最多 4 条");
                }
            }
            case "metric" -> {
                if (blank(props.getValue())) {
                    errors.add(p + ".props.value 不能为空");
                }
                if (blank(props.getLabel()) && blank(props.getTitle())) {
                    errors.add(p + ".props.label 不能为空");
                }
            }
            case "outro" -> {
                if (blank(props.getTitle())) {
                    errors.add(p + ".props.title 不能为空");
                }
            }
            default -> {
                // 其它 type 仅校验白名单已在上层完成
            }
        }
    }

    private static void validateInsightCompareStructure(StoryboardDto sb, List<String> errors) {
        boolean hasHook = false;
        boolean hasCompare = false;
        boolean hasOutro = false;
        int maxSameRun = 1;
        int run = 0;
        String prev = null;
        for (SceneDto sc : sb.getScenes()) {
            String t = sc.getType();
            if ("hook".equals(t) || "title".equals(t)) {
                hasHook = true;
            }
            if ("compare".equals(t)) {
                hasCompare = true;
            }
            if ("outro".equals(t)) {
                hasOutro = true;
            }
            if (t != null && t.equals(prev)) {
                run++;
                maxSameRun = Math.max(maxSameRun, run);
            } else {
                run = 1;
                prev = t;
            }
        }
        if (!hasHook) {
            errors.add("insight-compare 建议含 hook（或 title）开场");
        }
        if (!hasCompare) {
            errors.add("insight-compare 至少需要 1 个 compare 场景");
        }
        if (!hasOutro) {
            errors.add("insight-compare 需要 outro 收尾");
        }
        if (maxSameRun >= 3) {
            errors.add("禁止连续 3 个相同 type 场景，请调整叙事节奏");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean looksLikeUrl(String s) {
        String lower = s.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("file:");
    }
}
