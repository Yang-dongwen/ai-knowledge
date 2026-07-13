package com.dwcode.okxbot.aigen.domain;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

@Data
public class SceneDto {
    private String id;
    /** title | bullets | outro | hook | compare | … */
    private String type;
    private Integer startFrame;
    private Integer durationInFrames;
    /** 口播文案（Phase 1 可无真实音频，仍用于字幕） */
    private String narration;
    /** LLM 有时把 props 回成 ["title=…"] 数组，宽松解析 */
    @JsonDeserialize(using = FlexibleScenePropsDeserializer.class)
    private SceneProps props = new SceneProps();
}
