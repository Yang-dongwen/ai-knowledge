package com.dwcode.okxbot.aigen.domain.shot;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ShotOverlay {
    /**
     * 叠字布局：none | free | hook-center | lower-third | bullets-right | caption | big-word | corner
     * 默认 none（画面优先，避免模板感）
     */
    private String layout = "none";
    private String title;
    private String subtitle;
    private List<String> bullets = new ArrayList<>();
    /** center | top | bottom | left | right | lower-left | lower-right */
    private String position = "center";
    /** cinematic | bold-impact | soft | neon | minimal */
    private String style = "cinematic";
    /** none | fade | pop | slide_up | slide_left | typewriter | glitch */
    private String textAnim = "pop";
}
