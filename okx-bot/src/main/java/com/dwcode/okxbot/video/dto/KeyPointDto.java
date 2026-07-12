package com.dwcode.okxbot.video.dto;

import lombok.Data;

/**
 * 核心要点（带时间戳）。
 */
@Data
public class KeyPointDto {
    /** 时间戳，格式 HH:mm:ss 或 mm:ss */
    private String timestamp;
    private String point;
}
