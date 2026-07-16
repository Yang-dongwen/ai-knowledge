package com.dwcode.okxbot.video.port;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 多模态视觉理解聚合结果（Map 多片后合并）。
 */
@Data
public class VisualUnderstandingResult {
    private String modelId;
    private String protocol;
    private boolean partial;
    private String overallVisualSummary;
    private List<SceneItem> scenes = new ArrayList<>();
    private List<OnScreenTextItem> onScreenTexts = new ArrayList<>();
    private List<VisualKeyPointItem> visualKeyPoints = new ArrayList<>();
    private List<ChunkUnderstanding> chunks = new ArrayList<>();
    private String rawJsonPath;
    private int chunkCount;
    private long elapsedMs;

    @Data
    public static class SceneItem {
        private String startTimestamp;
        private String endTimestamp;
        private Double startSec;
        private Double endSec;
        private String description;
    }

    @Data
    public static class OnScreenTextItem {
        private String timestamp;
        private Double startSec;
        private String text;
    }

    @Data
    public static class VisualKeyPointItem {
        private String timestamp;
        private Double startSec;
        private String point;
        private String source;
    }

    @Data
    public static class ChunkUnderstanding {
        private int index;
        private double chunkStartSec;
        private double chunkEndSec;
        private String overallVisualSummary;
        private List<SceneItem> scenes = new ArrayList<>();
        private List<OnScreenTextItem> onScreenTexts = new ArrayList<>();
        private List<VisualKeyPointItem> visualKeyPoints = new ArrayList<>();
    }
}
