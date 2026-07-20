package com.dwcode.okxbot.video.dto;

import lombok.Data;

/**
 * 视频处理可选参数。
 */
@Data
public class VideoProcessOptions {
    /** 是否生成思维导图 */
    private Boolean extractMindMap = true;
    /** 是否生成 repurpose 脚本 */
    private Boolean generateRepurposeScript = true;
    /** 语言代码，如 zh / en */
    private String language = "zh";
    /**
     * LLM 供应商标识（对应 ai.providers 的 key，如 nvidia）。
     * 为空则使用 video.llm.provider / 默认供应商。
     */
    private String llmProvider;
    /**
     * LLM 模型 ID（如 deepseek-ai/deepseek-v4-flash）。
     * 为空则使用该供应商默认第一个模型。
     */
    private String llmModel;

    /**
     * 理解模式：download_only | audio_only | hybrid | omni_only。
     * download_only = 只下载视频，不转录/不总结/不画面理解。
     * 为空则使用 video.understanding.mode。
     */
    private String understandingMode;

    /** 多模态供应商（hybrid/omni_only）；空则用 video.understanding.provider */
    private String omniProvider;
    /** 多模态模型 ID；空则用 video.understanding.model */
    private String omniModel;
}
