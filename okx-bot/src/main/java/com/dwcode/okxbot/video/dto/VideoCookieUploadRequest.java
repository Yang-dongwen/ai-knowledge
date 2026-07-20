package com.dwcode.okxbot.video.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 上传平台 Cookie（浏览器 DevTools 复制的 Cookie 请求头字符串）。
 */
@Data
public class VideoCookieUploadRequest {

    /**
     * Cookie 请求头整串，如 {@code ttwid=...; s_v_web_id=...; ...}。
     * 可带或不带 {@code Cookie:} 前缀。
     */
    @NotBlank(message = "cookieHeader 不能为空")
    private String cookieHeader;

    /**
     * 平台：douyin（默认）| xiaohongshu | bilibili 等；用于选择写入文件与 domain。
     */
    private String platform = "douyin";
}
