package com.dwcode.okxbot.aigen.domain.shot;

import lombok.Data;

@Data
public class ShotlistMeta {
    private String title;
    private String language = "zh";
    private String aspectRatio = "9:16";
    private Integer fps = 30;
    private Integer width = 1080;
    private Integer height = 1920;
    private Integer targetDurationSec;
    private Integer durationInFrames;
    private String stylePreset = "cinematic-dark";
    private String pipelineMode = "visual";
}
