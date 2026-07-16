package com.dwcode.okxbot.aigen.domain.shot;

import lombok.Data;

@Data
public class ShotTransition {
    /** none | crossfade */
    private String type = "crossfade";
    private Integer durationFrames = 12;
}
