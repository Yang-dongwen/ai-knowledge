package com.dwcode.okxbot.imggen.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImgGenImageModelResponse {
    private String id;
    private String name;
    private String provider;
    private String invokeUrl;
    private int defaultSteps;
    private int maxSteps;
    /** nvidia-flux | nvidia-qwen | nvidia-openai-images */
    private String protocol;
    private String description;
    private boolean defaultModel;
}
