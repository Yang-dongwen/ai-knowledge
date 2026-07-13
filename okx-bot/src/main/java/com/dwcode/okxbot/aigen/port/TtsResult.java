package com.dwcode.okxbot.aigen.port;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TtsResult {
    /** 相对 workDir */
    private String relativeSrc;
    private long durationMs;
    private boolean mock;
}
