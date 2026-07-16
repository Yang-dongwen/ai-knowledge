package com.dwcode.okxbot.aigen.domain.shot;

import lombok.Data;

@Data
public class ShotDto {
    private String id;
    private Integer order;
    private Double durationSec;
    private Integer durationInFrames;
    private Integer startFrame;
    private ShotVisual visual = new ShotVisual();
    private ShotMotion motion = new ShotMotion();
    private ShotTransition transition = new ShotTransition();
    private ShotOverlay overlay = new ShotOverlay();
    private String narration;
    /** 相对任务目录：assets/audio/shot-1.mp3（visual TTS） */
    private String audioSrc;
    private String notes;
}
