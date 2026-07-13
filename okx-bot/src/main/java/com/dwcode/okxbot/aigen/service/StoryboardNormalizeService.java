package com.dwcode.okxbot.aigen.service;

import com.dwcode.okxbot.aigen.domain.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 规范化分镜：分辨率、连续时间轴、字幕占位等。
 */
@Service
public class StoryboardNormalizeService {

    public StoryboardDto normalize(StoryboardDto sb, String templateId, String aspectRatio,
                                   int targetDurationSec, String language, String titleHint) {
        if (sb.getMeta() == null) {
            sb.setMeta(new StoryboardMeta());
        }
        StoryboardMeta meta = sb.getMeta();
        meta.setTemplateId(templateId);
        meta.setLanguage(language != null ? language : "zh");
        if (meta.getTitle() == null || meta.getTitle().isBlank()) {
            meta.setTitle(titleHint != null ? titleHint : "未命名视频");
        }
        int fps = meta.getFps() != null && meta.getFps() > 0 ? meta.getFps() : 30;
        meta.setFps(fps);

        int[] wh = resolution(aspectRatio);
        meta.setWidth(wh[0]);
        meta.setHeight(wh[1]);

        if (sb.getStyle() == null) {
            sb.setStyle(new StoryboardStyle());
        }
        if (sb.getScenes() == null) {
            sb.setScenes(new ArrayList<>());
        }

        int targetFrames = Math.max(fps * 5, Math.min(fps * 90, targetDurationSec * fps));
        int n = Math.max(1, sb.getScenes().size());
        int base = targetFrames / n;
        int cursor = 0;
        for (int i = 0; i < sb.getScenes().size(); i++) {
            SceneDto sc = sb.getScenes().get(i);
            if (sc.getId() == null || sc.getId().isBlank()) {
                sc.setId("s" + (i + 1));
            }
            int dur = sc.getDurationInFrames() != null && sc.getDurationInFrames() > 0
                    ? sc.getDurationInFrames()
                    : (i == n - 1 ? Math.max(fps, targetFrames - cursor) : base);
            if (i == n - 1) {
                dur = Math.max(fps, targetFrames - cursor);
            }
            sc.setStartFrame(cursor);
            sc.setDurationInFrames(dur);
            if (sc.getProps() == null) {
                sc.setProps(new SceneProps());
            }
            if (sc.getNarration() == null) {
                sc.setNarration("");
            }
            cursor += dur;
        }
        meta.setDurationInFrames(cursor);
        sb.setVersion("1.0");

        if (sb.getAudio() == null) {
            sb.setAudio(new AudioBlockDto());
        }
        if (sb.getSubtitles() == null) {
            sb.setSubtitles(new ArrayList<>());
        }
        return sb;
    }

    /** 按 narration 粗估字幕时间轴（无真实音频时） */
    public void rebuildSubtitlesFromScenes(StoryboardDto sb) {
        List<SubtitleDto> subs = new ArrayList<>();
        long t = 0;
        int fps = sb.getMeta() != null && sb.getMeta().getFps() != null ? sb.getMeta().getFps() : 30;
        for (SceneDto sc : sb.getScenes()) {
            long ms;
            if (sb.getAudio() != null && sb.getAudio().getTracks() != null) {
                long trackMs = sb.getAudio().getTracks().stream()
                        .filter(tr -> sc.getId().equals(tr.getSceneId()) && tr.getDurationMs() != null)
                        .mapToLong(AudioTrackDto::getDurationMs)
                        .findFirst()
                        .orElse(-1L);
                if (trackMs > 0) {
                    ms = trackMs;
                } else {
                    ms = estimateNarrationMs(sc.getNarration(), sc.getDurationInFrames(), fps);
                }
            } else {
                ms = estimateNarrationMs(sc.getNarration(), sc.getDurationInFrames(), fps);
            }
            SubtitleDto sub = new SubtitleDto();
            sub.setStartMs(t);
            sub.setEndMs(t + ms);
            sub.setText(sc.getNarration() != null ? sc.getNarration() : "");
            subs.add(sub);
            t += ms;
        }
        sb.setSubtitles(subs);
    }

    public static long estimateNarrationMs(String narration, Integer durationInFrames, int fps) {
        if (durationInFrames != null && durationInFrames > 0 && fps > 0) {
            return durationInFrames * 1000L / fps;
        }
        int chars = narration == null ? 0 : narration.length();
        // 约 4 字/秒，最少 1.5s
        return Math.max(1500L, chars * 250L);
    }

    /**
     * 按 audio.tracks 的真实时长重排场景时间轴，并重建字幕。
     *
     * @param tailPaddingFrames 每场结尾额外留白帧，避免语音被截断
     */
    public void realignByAudioTracks(StoryboardDto sb, int tailPaddingFrames) {
        if (sb == null || sb.getScenes() == null || sb.getScenes().isEmpty()) {
            return;
        }
        int fps = sb.getMeta() != null && sb.getMeta().getFps() != null && sb.getMeta().getFps() > 0
                ? sb.getMeta().getFps() : 30;
        int pad = Math.max(0, tailPaddingFrames);
        int cursor = 0;
        for (SceneDto sc : sb.getScenes()) {
            long ms = -1L;
            if (sb.getAudio() != null && sb.getAudio().getTracks() != null) {
                ms = sb.getAudio().getTracks().stream()
                        .filter(tr -> sc.getId() != null && sc.getId().equals(tr.getSceneId()))
                        .filter(tr -> tr.getDurationMs() != null && tr.getDurationMs() > 0)
                        .mapToLong(AudioTrackDto::getDurationMs)
                        .findFirst()
                        .orElse(-1L);
            }
            if (ms <= 0) {
                ms = estimateNarrationMs(sc.getNarration(), sc.getDurationInFrames(), fps);
            }
            int frames = (int) Math.ceil(ms * fps / 1000.0) + pad;
            frames = Math.max(fps / 2, frames); // 至少 0.5s
            sc.setStartFrame(cursor);
            sc.setDurationInFrames(frames);
            cursor += frames;
        }
        if (sb.getMeta() == null) {
            sb.setMeta(new StoryboardMeta());
        }
        sb.getMeta().setFps(fps);
        sb.getMeta().setDurationInFrames(cursor);
        rebuildSubtitlesFromScenes(sb);
    }

    private static int[] resolution(String aspect) {
        if ("16:9".equals(aspect)) {
            return new int[]{1920, 1080};
        }
        if ("1:1".equals(aspect)) {
            return new int[]{1080, 1080};
        }
        return new int[]{1080, 1920};
    }
}
