package com.dwcode.okxbot.video.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化摘要内容。
 */
@Data
public class VideoSummaryPart {
    private List<KeyPointDto> keyPoints = new ArrayList<>();
    private List<ChapterDto> chapters = new ArrayList<>();
    private String mindMapMarkdown;
    private String repurposeScript;

    /** 画面总述 */
    private String visualSummary;
    private List<KeyPointDto> visualKeyPoints = new ArrayList<>();
    private List<String> onScreenTexts = new ArrayList<>();
    private List<String> scenes = new ArrayList<>();
    private Boolean multimodal;
    private String understandingMode;
    private Boolean partialVisual;
    private Boolean degraded;
    private String degradeReason;
}
