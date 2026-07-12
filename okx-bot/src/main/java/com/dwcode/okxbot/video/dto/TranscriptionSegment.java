package com.dwcode.okxbot.video.dto;

import lombok.Data;

/**
 * 转录分段（带时间戳）。
 */
@Data
public class TranscriptionSegment {
    private int id;
    /** 开始秒 */
    private double start;
    /** 结束秒 */
    private double end;
    private String text;
}
