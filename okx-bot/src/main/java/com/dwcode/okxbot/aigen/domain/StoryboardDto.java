package com.dwcode.okxbot.aigen.domain;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 分镜契约 v1.0 — LLM / TTS / Remotion 共用。
 */
@Data
public class StoryboardDto {
    private String version = "1.0";
    private StoryboardMeta meta = new StoryboardMeta();
    private StoryboardStyle style = new StoryboardStyle();
    private List<SceneDto> scenes = new ArrayList<>();
    private AudioBlockDto audio = new AudioBlockDto();
    private List<SubtitleDto> subtitles = new ArrayList<>();
}
