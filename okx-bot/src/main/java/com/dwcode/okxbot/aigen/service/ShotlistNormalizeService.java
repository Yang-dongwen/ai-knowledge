package com.dwcode.okxbot.aigen.service;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.domain.shot.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ShotlistNormalizeService {

    private final AigenProperties aigenProperties;

    public ShotlistDto normalize(ShotlistDto list,
                                 String aspectRatio,
                                 int targetDurationSec,
                                 String language,
                                 String stylePreset,
                                 String audioMode,
                                 String titleHint) {
        if (list == null) {
            list = new ShotlistDto();
        }
        list.setVersion("vt-1.0");
        if (list.getMeta() == null) {
            list.setMeta(new ShotlistMeta());
        }
        ShotlistMeta meta = list.getMeta();
        meta.setPipelineMode("visual");
        meta.setLanguage(language != null ? language : "zh");
        meta.setAspectRatio(aspectRatio != null ? aspectRatio : "9:16");
        meta.setTargetDurationSec(targetDurationSec);
        meta.setStylePreset(stylePreset != null ? stylePreset
                : aigenProperties.getVisual().getDefaultStylePreset());
        meta.setFps(30);
        int[] wh = resolveSize(meta.getAspectRatio());
        meta.setWidth(wh[0]);
        meta.setHeight(wh[1]);
        if (meta.getTitle() == null || meta.getTitle().isBlank()) {
            meta.setTitle(titleHint != null ? titleHint : "Visual Timeline");
        }

        if (list.getAudio() == null) {
            list.setAudio(new ShotlistAudio());
        }
        String am = audioMode != null ? audioMode : aigenProperties.getVisual().getDefaultAudioMode();
        list.getAudio().setMode(am != null ? am.toLowerCase(Locale.ROOT) : "none");

        int fps = meta.getFps() != null ? meta.getFps() : 30;
        int frame = 0;
        int order = 1;
        if (list.getShots() != null) {
            for (ShotDto s : list.getShots()) {
                if (s.getId() == null || s.getId().isBlank()) {
                    s.setId("shot-" + order);
                }
                s.setOrder(order++);
                double sec = s.getDurationSec() != null ? s.getDurationSec() : 3.2;
                sec = Math.min(10.0, Math.max(1.2, sec));
                s.setDurationSec(sec);
                int df = Math.max(1, (int) Math.round(sec * fps));
                s.setDurationInFrames(df);
                s.setStartFrame(frame);
                frame += df;

                if (s.getVisual() == null) {
                    s.setVisual(new ShotVisual());
                }
                if (s.getVisual().getType() == null || s.getVisual().getType().isBlank()) {
                    s.getVisual().setType("ai_image");
                }
                if (s.getMotion() == null) {
                    s.setMotion(new ShotMotion());
                }
                if (s.getMotion().getType() == null || s.getMotion().getType().isBlank()) {
                    String def = aigenProperties.getVisual().getMotionDefault();
                    s.getMotion().setType(def != null && !def.isBlank() ? def : "auto");
                }
                // 无 params 时给 auto/运镜一个默认 intensity，便于 Remotion 发挥
                if (s.getMotion().getParams() == null) {
                    s.getMotion().setParams(new java.util.HashMap<>());
                }
                if (!s.getMotion().getParams().containsKey("intensity")) {
                    // 镜序交替强弱，避免全片匀速
                    double intensity = 0.45 + 0.12 * ((order - 1) % 5);
                    s.getMotion().getParams().put("intensity", Math.min(0.95, intensity));
                }
                if (s.getTransition() == null) {
                    s.setTransition(new ShotTransition());
                }
                if (s.getTransition().getType() == null || s.getTransition().getType().isBlank()) {
                    s.getTransition().setType(order % 4 == 0 ? "dip_black" : "crossfade");
                }
                if (s.getTransition().getDurationFrames() == null) {
                    s.getTransition().setDurationFrames(10);
                }
                if (s.getOverlay() == null) {
                    s.setOverlay(new ShotOverlay());
                }
                // 默认不叠字，减少模板感；有 title 且 layout 空时才给 free
                if (s.getOverlay().getLayout() == null || s.getOverlay().getLayout().isBlank()) {
                    boolean hasText = (s.getOverlay().getTitle() != null && !s.getOverlay().getTitle().isBlank())
                            || (s.getOverlay().getSubtitle() != null && !s.getOverlay().getSubtitle().isBlank());
                    s.getOverlay().setLayout(hasText ? "free" : "none");
                }
                if (s.getOverlay().getStyle() == null || s.getOverlay().getStyle().isBlank()) {
                    s.getOverlay().setStyle("cinematic");
                }
                if (s.getOverlay().getTextAnim() == null || s.getOverlay().getTextAnim().isBlank()) {
                    s.getOverlay().setTextAnim("pop");
                }
                if (s.getOverlay().getPosition() == null || s.getOverlay().getPosition().isBlank()) {
                    s.getOverlay().setPosition("center");
                }
                // TTS 模式：补全空 narration，避免后续 Edge sanitize 后「文本为空」
                String audioModeLower = list.getAudio() != null && list.getAudio().getMode() != null
                        ? list.getAudio().getMode().toLowerCase(Locale.ROOT)
                        : (am != null ? am.toLowerCase(Locale.ROOT) : "none");
                if (("tts".equals(audioModeLower) || "tts_bgm".equals(audioModeLower))
                        && (s.getNarration() == null || s.getNarration().isBlank())) {
                    s.setNarration(fillNarrationFromOverlay(s, titleHint, order - 1));
                }
            }
        }
        meta.setDurationInFrames(frame);
        return list;
    }

    private static String fillNarrationFromOverlay(ShotDto s, String titleHint, int order) {
        StringBuilder sb = new StringBuilder();
        if (s.getOverlay() != null) {
            if (s.getOverlay().getTitle() != null && !s.getOverlay().getTitle().isBlank()) {
                sb.append(s.getOverlay().getTitle().trim());
            }
            if (s.getOverlay().getSubtitle() != null && !s.getOverlay().getSubtitle().isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append('。');
                }
                sb.append(s.getOverlay().getSubtitle().trim());
            }
            if (s.getOverlay().getBullets() != null) {
                for (String b : s.getOverlay().getBullets()) {
                    if (b != null && !b.isBlank()) {
                        if (!sb.isEmpty()) {
                            sb.append('。');
                        }
                        sb.append(b.trim());
                    }
                }
            }
        }
        if (!sb.isEmpty()) {
            return sb.toString();
        }
        String base = titleHint != null && !titleHint.isBlank() ? titleHint.trim() : "本片";
        if (base.length() > 24) {
            base = base.substring(0, 24);
        }
        return base + "，第" + order + "镜。";
    }

    private static int[] resolveSize(String aspect) {
        if ("16:9".equals(aspect)) {
            return new int[]{1920, 1080};
        }
        if ("1:1".equals(aspect)) {
            return new int[]{1080, 1080};
        }
        return new int[]{1080, 1920};
    }
}
