package com.dwcode.okxbot.aigen.domain;

import lombok.Data;

@Data
public class SceneDto {
    private String id;
    /** title | bullets | outro */
    private String type;
    private Integer startFrame;
    private Integer durationInFrames;
    /** 口播文案（Phase 1 可无真实音频，仍用于字幕） */
    private String narration;
    private SceneProps props = new SceneProps();
}
