package com.dwcode.okxbot.aigen.domain.shot;

import lombok.Data;

@Data
public class ShotVisual {
    /** ai_image | solid | gradient | user_image | ai_video */
    private String type = "ai_image";
    /** 画面描述（与用户同语言，用于展示/口播对齐） */
    private String prompt;
    /**
     * 英文画面描述（可选备份）。
     * 默认出图跟随用户语言用 {@link #prompt}；仅当用户/配置明确要求英文出图时才优先用本字段。
     */
    private String promptEn;
    private String negativePrompt;
    private Long seed;
    /**
     * 主视觉相对路径（页面缩略图 / 预览优先用静图）。
     * 例：assets/visual/shot-1.jpg
     */
    private String assetPath;
    /**
     * 动效视频相对路径（Remotion 合成优先用）。
     * 例：assets/visual/shot-1.mp4；空则合成时回退 assetPath。
     */
    private String videoPath;
    /** 渲染时注入的 HTTP URL（通常指向 videoPath 或 assetPath） */
    private String assetUrl;
}
