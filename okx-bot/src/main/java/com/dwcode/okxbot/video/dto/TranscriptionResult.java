package com.dwcode.okxbot.video.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Whisper 转录完整结果。
 */
@Data
public class TranscriptionResult {
    private String text;
    private String language;
    private Double durationSeconds;
    private List<TranscriptionSegment> segments = new ArrayList<>();
}
