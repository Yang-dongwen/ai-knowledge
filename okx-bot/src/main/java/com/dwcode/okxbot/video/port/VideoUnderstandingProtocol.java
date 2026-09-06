package com.dwcode.okxbot.video.port;

import java.util.Locale;

/**
 * 视频理解 protocol 字符串约定（yml / 任务字段 / 结果回写）。
 */
public final class VideoUnderstandingProtocol {

    public static final String MOCK = "mock";
    public static final String AUTO = "auto";
    public static final String OMNI = "nvidia-omni-chat";
    public static final String FRAME = "frame-vlm";
    public static final String FRAME_FALLBACK = "frame-vlm-fallback";

    private VideoUnderstandingProtocol() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isMock(String protocol, boolean configMock) {
        return configMock || MOCK.equals(normalize(protocol));
    }

    /** 强制抽帧，不走 Omni。 */
    public static boolean forceFrame(String protocol) {
        String p = normalize(protocol);
        return FRAME.equals(p);
    }

    /** auto 或显式 Omni 才先走媒体 Omni。 */
    public static boolean useOmni(String protocol) {
        String p = normalize(protocol);
        return AUTO.equals(p) || OMNI.equals(p);
    }
}
