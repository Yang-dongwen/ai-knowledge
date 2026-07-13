package com.dwcode.okxbot.aigen.domain;

import lombok.Data;

@Data
public class SubtitleDto {
    private Long startMs;
    private Long endMs;
    private String text;
}
