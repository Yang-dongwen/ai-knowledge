package com.dwcode.okxbot.video.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Cookie 文件状态（不回传 Cookie 明文）。
 */
@Data
@Builder
public class VideoCookieStatusResponse {

    private String platform;
    /** 是否已配置可用 cookies 文件 */
    private boolean configured;
    /** 文件是否存在 */
    private boolean fileExists;
    private String filePath;
    private Integer cookieCount;
    private Long fileSizeBytes;
    /** ISO 本地时间字符串 */
    private String lastModifiedAt;
    private String hint;
}
