package com.dwcode.okxbot.imggen.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImgGenEnhanceResponse {

    /** 用户原文 */
    private String originalPrompt;

    /** 润色后的提示词（前端写回输入框） */
    private String enhancedPrompt;

    private String llmProvider;
    private String llmModel;

    /** 润色耗时 ms */
    private Long latencyMs;
}
