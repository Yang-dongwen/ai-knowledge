package com.dwcode.okxbot.video.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * ASR 分层摘要（Fuse / 文本总结只消费本结构，禁止塞入全量字幕）。
 */
@Data
public class TranscriptDigest {
    private int windowCount;
    private int mapLlmCalls;
    private boolean truncatedGuardHit;
    /** reduce 后的口播总述 */
    private String overallText;
    private List<DigestWindow> windows = new ArrayList<>();

    @Data
    public static class DigestWindow {
        private double startSec;
        private double endSec;
        private String partialSummary;
        private List<String> keyPoints = new ArrayList<>();
    }
}
