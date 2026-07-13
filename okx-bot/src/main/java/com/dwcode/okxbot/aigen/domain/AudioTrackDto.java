package com.dwcode.okxbot.aigen.domain;

import lombok.Data;

@Data
public class AudioTrackDto {
    private String sceneId;
    /** 相对 workDir，如 assets/audio/s1.mp3；mock 时可为空 */
    private String src;
    private Long durationMs;
    private boolean mock;
}
