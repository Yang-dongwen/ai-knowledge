package com.dwcode.okxbot.aigen.service;

import com.dwcode.okxbot.aigen.domain.SceneDto;
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

    private static boolean looksLikeUrl(String s) {
        String lower = s.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("file:");
    }
}
