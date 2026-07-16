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
                double sec = s.getDurationSec() != null ? s.getDurationSec() : 3.5;
                sec = Math.min(8.0, Math.max(1.5, sec));
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
                    s.getMotion().setType(aigenProperties.getVisual().getMotionDefault());
                }
                if (s.getTransition() == null) {
                    s.setTransition(new ShotTransition());
                }
                if (s.getTransition().getType() == null) {
                    s.getTransition().setType("crossfade");
                }
                if (s.getTransition().getDurationFrames() == null) {
                    s.getTransition().setDurationFrames(12);
                }
                if (s.getOverlay() == null) {
                    s.setOverlay(new ShotOverlay());
                }
                if (s.getOverlay().getLayout() == null) {
                    s.getOverlay().setLayout("hook-center");
                }
            }
        }
        meta.setDurationInFrames(frame);
        return list;
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
