package com.dwcode.okxbot.imggen.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ImgGenTaskResponse {
    private String id;
    private String title;
    private String prompt;
    private String enhancedPrompt;
    private String negativePrompt;
    private String status;
    private String currentStep;
    private Integer progress;
    private String provider;
    private String model;
    private String aspectRatio;
    private Integer width;
    private Integer height;
    private Integer steps;
    private Integer n;
    private Long seed;
    private Boolean enhanceEnabled;
    private String llmProvider;
    private String llmModel;
    private String errorMessage;
    private Boolean outputAvailable;
    private List<ImageFileDto> images;
    private Long enhanceDurationMs;
    private Long generateDurationMs;
    private Long totalDurationMs;
    private String startedAt;
    private String finishedAt;
    private String createdAt;
    private String updatedAt;

    @Data
    @Builder
    public static class ImageFileDto {
        private int index;
        private String path;
        private String mediaUrl;
        private Integer width;
        private Integer height;
        private Long seed;
    }
}
