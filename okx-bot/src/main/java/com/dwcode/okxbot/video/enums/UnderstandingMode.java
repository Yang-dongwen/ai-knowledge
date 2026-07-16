package com.dwcode.okxbot.video.enums;

import java.util.Locale;

/**
 * 视频理解模式。
 * <ul>
 *   <li>audio_only — 现网：Whisper + 文本总结</li>
 *   <li>hybrid — Whisper + 多模态画面理解 + Fuse</li>
 *   <li>omni_only — 仅多模态（可选不跑 Whisper）</li>
 * </ul>
 */
public enum UnderstandingMode {
    AUDIO_ONLY,
    HYBRID,
    OMNI_ONLY;

    public boolean needsWhisper() {
        return this == AUDIO_ONLY || this == HYBRID;
    }

    public boolean needsOmni() {
        return this == HYBRID || this == OMNI_ONLY;
    }

    public static UnderstandingMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUDIO_ONLY;
        }
        String n = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (n) {
            case "hybrid" -> HYBRID;
            case "omni_only", "omni", "omni-only" -> OMNI_ONLY;
            case "audio_only", "audio", "asr" -> AUDIO_ONLY;
            default -> AUDIO_ONLY;
        };
    }

    public String wireValue() {
        return switch (this) {
            case AUDIO_ONLY -> "audio_only";
            case HYBRID -> "hybrid";
            case OMNI_ONLY -> "omni_only";
        };
    }
}
