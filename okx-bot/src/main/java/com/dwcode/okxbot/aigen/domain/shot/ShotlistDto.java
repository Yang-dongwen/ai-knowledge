package com.dwcode.okxbot.aigen.domain.shot;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Visual Timeline 镜头表契约 vt-1.0。
 */
@Data
public class ShotlistDto {
    private String version = "vt-1.0";
    private ShotlistMeta meta = new ShotlistMeta();
    private ShotlistAudio audio = new ShotlistAudio();
    private List<ShotDto> shots = new ArrayList<>();
}
