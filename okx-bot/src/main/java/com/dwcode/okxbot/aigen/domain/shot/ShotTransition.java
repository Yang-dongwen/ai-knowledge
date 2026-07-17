package com.dwcode.okxbot.aigen.domain.shot;

import lombok.Data;

@Data
public class ShotTransition {
    /** crossfade | hard_cut | flash | dip_black | dip_white | wipe_left | wipe_right */
    private String type = "crossfade";
    private Integer durationFrames = 10;
}
