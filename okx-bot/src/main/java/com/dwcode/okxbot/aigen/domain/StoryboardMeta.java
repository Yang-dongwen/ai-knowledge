package com.dwcode.okxbot.aigen.domain;

import lombok.Data;

@Data
public class StoryboardMeta {
    private String title;
    private String language = "zh";
    private String templateId;
    private Integer fps = 30;
    private Integer width = 1080;
    private Integer height = 1920;
    private Integer durationInFrames;
}
