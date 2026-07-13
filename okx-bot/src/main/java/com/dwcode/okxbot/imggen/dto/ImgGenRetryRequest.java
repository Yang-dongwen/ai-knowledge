package com.dwcode.okxbot.imggen.dto;

import lombok.Data;

@Data
public class ImgGenRetryRequest {
    /** 切换生图模型 */
    private String imageModel;
    private String imageProvider;
    /** 切换润色 Chat 模型 */
    private String llmProvider;
    private String llmModel;
    private Boolean enhancePrompt;
    private Long seed;
}
