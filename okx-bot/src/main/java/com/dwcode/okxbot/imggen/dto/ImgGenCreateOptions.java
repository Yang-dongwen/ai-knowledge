package com.dwcode.okxbot.imggen.dto;

import lombok.Data;

@Data
public class ImgGenCreateOptions {
    /** 1:1 / 16:9 / 9:16 */
    private String aspectRatio;
    /** 张数 1~maxN */
    private Integer n;
    private Long seed;
    private Integer steps;
    /**
     * 生图模型 ID（如 black-forest-labs/flux.1-schnell）。
     * 对应 imggen.flux.models[].id；空则用默认模型。
     */
    private String imageModel;
    /** 生图供应商 key，默认 nvidia（与 yml flux.provider-key 一致） */
    private String imageProvider;
    /** 是否 LLM 润色 prompt */
    private Boolean enhancePrompt;
    /** 润色用 Chat LLM 供应商 */
    private String llmProvider;
    /** 润色用 Chat LLM 模型 ID */
    private String llmModel;
    private String negativePrompt;
}
