package com.dwcode.okxbot.video.dto;

import lombok.Data;

/**
 * 视频处理可选参数。
 */
@Data
public class VideoProcessOptions {
    /** 是否生成思维导图 */
    private Boolean extractMindMap = true;
    /** 是否生成 repurpose 脚本 */
    private Boolean generateRepurposeScript = true;
    /** 语言代码，如 zh / en */
    private String language = "zh";
}
