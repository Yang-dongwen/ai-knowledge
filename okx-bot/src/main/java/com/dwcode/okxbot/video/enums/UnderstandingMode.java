package com.dwcode.okxbot.video.enums;

import java.util.Locale;

/**
 * 视频理解模式。
 * <ul>
 *   <li>download_only — 仅下载视频（不转录、不画面理解、不 LLM）</li>
 *   <li>audio_only — Whisper + 文本总结</li>
 *   <li>hybrid — Whisper + 多模态画面理解 + Fuse</li>
 *   <li>omni_only — 仅多模态（可选不跑 Whisper）</li>
 * </ul>
 */
public enum UnderstandingMode {
    DOWNLOAD_ONLY,
    AUDIO_ONLY,
    HYBRID,
    OMNI_ONLY;

    public boolean needsWhisper() {
        return this == AUDIO_ONLY || this == HYBRID;
    }

    public boolean needsOmni() {
        return this == HYBRID || this == OMNI_ONLY;
    }

    public boolean needsLlm() {
        return this != DOWNLOAD_ONLY;
    }

    /** 仅下载，流水线在 DOWNLOADING 后直接 SUCCESS */
    public boolean isDownloadOnly() {
        return this == DOWNLOAD_ONLY;
    }

    public static UnderstandingMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUDIO_ONLY;
        }
        String n = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (n) {
            case "download_only", "download", "video_only", "only_download" -> DOWNLOAD_ONLY;
            case "hybrid" -> HYBRID;
            case "omni_only", "omni", "omni-only" -> OMNI_ONLY;
            case "audio_only", "audio", "asr" -> AUDIO_ONLY;
            default -> AUDIO_ONLY;
        };
    }

    public String wireValue() {
        return switch (this) {
            case DOWNLOAD_ONLY -> "download_only";
            case AUDIO_ONLY -> "audio_only";
            case HYBRID -> "hybrid";
            case OMNI_ONLY -> "omni_only";
        };
    }
}
