package com.dwcode.okxbot.aigen.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AigenShotSummary {
    private String id;
    private Integer order;
    private Double durationSec;
    private String title;
    private String visualType;
    private String assetPath;
    private boolean imageAvailable;
    private String audioSrc;
    private boolean audioAvailable;
    private String motionType;
    private String layout;
    private String promptPreview;
}
