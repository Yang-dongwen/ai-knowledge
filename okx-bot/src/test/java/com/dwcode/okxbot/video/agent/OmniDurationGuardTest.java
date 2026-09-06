package com.dwcode.okxbot.video.agent;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.enums.UnderstandingMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OmniDurationGuardTest {

    @Test
    void audioOnlyIgnoresDuration() {
        VideoProperties.Understanding cfg = new VideoProperties.Understanding();
        cfg.setOmniMaxDurationSeconds(10);
        assertSame(UnderstandingMode.AUDIO_ONLY,
                OmniDurationGuard.enforce(UnderstandingMode.AUDIO_ONLY, 999.0, cfg, 1L));
    }

    @Test
    void withinLimitKeepsHybrid() {
        VideoProperties.Understanding cfg = new VideoProperties.Understanding();
        cfg.setOmniMaxDurationSeconds(100);
        assertSame(UnderstandingMode.HYBRID,
                OmniDurationGuard.enforce(UnderstandingMode.HYBRID, 50.0, cfg, 1L));
    }

    @Test
    void overLimitRejectsByDefault() {
        VideoProperties.Understanding cfg = new VideoProperties.Understanding();
        cfg.setOmniMaxDurationSeconds(10);
        cfg.setOnOmniTooLong("reject");
        assertThrows(BusinessException.class,
                () -> OmniDurationGuard.enforce(UnderstandingMode.HYBRID, 20.0, cfg, 1L));
    }

    @Test
    void overLimitForceAudioDowngradesHybrid() {
        VideoProperties.Understanding cfg = new VideoProperties.Understanding();
        cfg.setOmniMaxDurationSeconds(10);
        cfg.setOnOmniTooLong("force_audio");
        assertEquals(UnderstandingMode.AUDIO_ONLY,
                OmniDurationGuard.enforce(UnderstandingMode.HYBRID, 20.0, cfg, 1L));
    }

    @Test
    void forceAudioDoesNotDowngradeOmniOnly() {
        VideoProperties.Understanding cfg = new VideoProperties.Understanding();
        cfg.setOmniMaxDurationSeconds(10);
        cfg.setOnOmniTooLong("force_audio");
        assertThrows(BusinessException.class,
                () -> OmniDurationGuard.enforce(UnderstandingMode.OMNI_ONLY, 20.0, cfg, 1L));
    }

    @Test
    void nullDurationKeepsMode() {
        VideoProperties.Understanding cfg = new VideoProperties.Understanding();
        cfg.setOmniMaxDurationSeconds(1);
        assertSame(UnderstandingMode.HYBRID,
                OmniDurationGuard.enforce(UnderstandingMode.HYBRID, null, cfg, 1L));
    }
}
