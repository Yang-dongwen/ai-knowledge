package com.dwcode.okxbot.video.agent;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.enums.UnderstandingMode;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

/**
 * 多模态时长软顶：超限时 reject 或把 hybrid 降为 audio_only。
 */
@Slf4j
public final class OmniDurationGuard {

    private OmniDurationGuard() {
    }

    public static UnderstandingMode enforce(UnderstandingMode mode,
                                            Double durationSec,
                                            VideoProperties.Understanding cfg,
                                            Long taskId) {
        if (mode == null || !mode.needsOmni() || durationSec == null) {
            return mode;
        }
        if (cfg == null) {
            return mode;
        }
        int max = cfg.getOmniMaxDurationSeconds() > 0
                ? cfg.getOmniMaxDurationSeconds()
                : cfg.getHybridMaxDurationSeconds();
        if (max <= 0 || durationSec <= max) {
            return mode;
        }
        String action = cfg.getOnOmniTooLong() != null
                ? cfg.getOnOmniTooLong().toLowerCase(Locale.ROOT) : "reject";
        if ("force_audio".equals(action) && mode == UnderstandingMode.HYBRID) {
            log.warn("视频超 Omni 软顶，强制 audio_only: taskId={}, duration={}, max={}",
                    taskId, durationSec, max);
            return UnderstandingMode.AUDIO_ONLY;
        }
        throw new BusinessException("视频时长 " + durationSec.intValue()
                + "s 超过多模态上限 " + max + "s，请缩短视频或改用 audio_only");
    }
}
