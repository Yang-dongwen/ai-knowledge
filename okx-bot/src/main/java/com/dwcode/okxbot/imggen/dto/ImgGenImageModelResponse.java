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
    /** 预留：当前仅支持 NVIDIA FLUX GenAI（nvidia-flux） */
    private String protocol;
    private String description;
    private boolean defaultModel;
}
