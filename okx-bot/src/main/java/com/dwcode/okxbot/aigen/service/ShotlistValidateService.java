package com.dwcode.okxbot.aigen.service;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.domain.shot.ShotDto;
import com.dwcode.okxbot.aigen.domain.shot.ShotlistDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ShotlistValidateService {

    private static final Set<String> VISUAL_TYPES = Set.of("ai_image", "solid", "gradient", "user_image", "ai_video");
    private static final Set<String> MOTION_TYPES = Set.of("static", "ken_burns", "zoom_in", "zoom_out", "pan_left", "pan_right");
    private static final Set<String> LAYOUTS = Set.of("none", "hook-center", "lower-third", "bullets-right", "caption");
    private static final Set<String> AUDIO_MODES = Set.of("none", "bgm_only", "tts");

    private final AigenProperties aigenProperties;

    public List<String> validate(ShotlistDto list, int targetDurationSec) {
        List<String> errors = new ArrayList<>();
        if (list == null) {
            errors.add("shotlist 为空");
            return errors;
        }
        if (list.getShots() == null || list.getShots().isEmpty()) {
            errors.add("shots 不能为空");
            return errors;
        }
        int min = aigenProperties.getVisual().getMinShots();
        int max = aigenProperties.getVisual().getMaxShots();
        int n = list.getShots().size();
        if (n < min || n > max) {
            errors.add("shots 数量需在 " + min + "～" + max + "，当前 " + n);
        }

        if (list.getAudio() != null && list.getAudio().getMode() != null) {
            String mode = list.getAudio().getMode().toLowerCase(Locale.ROOT);
            if (!AUDIO_MODES.contains(mode)) {
                errors.add("audio.mode 非法: " + list.getAudio().getMode());
            }
        }

        double totalSec = 0;
        for (int i = 0; i < list.getShots().size(); i++) {
            ShotDto s = list.getShots().get(i);
            String prefix = "shots[" + i + "]";
            if (s.getId() == null || s.getId().isBlank()) {
                errors.add(prefix + ".id 不能为空");
            }
            double d = s.getDurationSec() != null ? s.getDurationSec() : 0;
            if (d < 1.5 || d > 8.0) {
                errors.add(prefix + ".durationSec 需在 1.5～8，当前 " + d);
            }
            totalSec += Math.max(0, d);
            if (s.getVisual() == null) {
                errors.add(prefix + ".visual 不能为空");
                continue;
            }
            String vt = s.getVisual().getType() != null ? s.getVisual().getType().toLowerCase(Locale.ROOT) : "";
            if (!VISUAL_TYPES.contains(vt)) {
                errors.add(prefix + ".visual.type 非法: " + s.getVisual().getType());
            }
            if ("ai_image".equals(vt)) {
                String p = s.getVisual().getPrompt();
                if (p == null || p.isBlank()) {
                    errors.add(prefix + ".visual.prompt 不能为空");
                } else if (p.length() > 800) {
                    errors.add(prefix + ".visual.prompt 过长");
                } else if (p.toLowerCase(Locale.ROOT).contains("http://")
                        || p.toLowerCase(Locale.ROOT).contains("https://")) {
                    errors.add(prefix + " 禁止在 prompt 中放外链");
                }
            }
            if (s.getMotion() != null && s.getMotion().getType() != null
                    && !MOTION_TYPES.contains(s.getMotion().getType().toLowerCase(Locale.ROOT))) {
                errors.add(prefix + ".motion.type 非法: " + s.getMotion().getType());
            }
            if (s.getOverlay() != null && s.getOverlay().getLayout() != null
                    && !LAYOUTS.contains(s.getOverlay().getLayout().toLowerCase(Locale.ROOT))) {
                errors.add(prefix + ".overlay.layout 非法: " + s.getOverlay().getLayout());
            }
            if (s.getOverlay() != null && s.getOverlay().getTitle() != null
                    && s.getOverlay().getTitle().length() > 40) {
                errors.add(prefix + ".overlay.title 过长");
            }
            if (s.getVisual().getAssetPath() != null) {
                String ap = s.getVisual().getAssetPath().replace('\\', '/');
                if (ap.contains("..") || ap.startsWith("/") || ap.contains(":")) {
                    errors.add(prefix + ".visual.assetPath 非法");
                }
            }
        }

        int minD = aigenProperties.getMinDurationSec();
        int maxD = aigenProperties.getMaxDurationSec();
        if (totalSec < minD * 0.5 || totalSec > maxD * 1.3) {
            errors.add(String.format(Locale.ROOT,
                    "镜头总时长约 %.1fs，与目标 %ds 偏差过大", totalSec, targetDurationSec));
        }
        return errors;
    }
}
