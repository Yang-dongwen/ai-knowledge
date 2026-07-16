package com.dwcode.okxbot.aigen.domain.shot;

import lombok.Data;

@Data
public class ShotVisual {
    /** ai_image | solid | gradient | user_image | ai_video */
    private String type = "ai_image";
    private String prompt;
    private String negativePrompt;
    private Long seed;
    /** 相对任务目录：assets/visual/shot-1.jpg */
    private String assetPath;
    /** 渲染时注入的 HTTP URL */
    private String assetUrl;
}
