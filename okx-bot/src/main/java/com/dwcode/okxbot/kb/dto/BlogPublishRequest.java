package com.dwcode.okxbot.kb.dto;

import lombok.Data;

import java.util.List;

/**
 * 发布到 Halo 时可选分类、标签（展示名，与 Halo 后台一致）。
 */
@Data
public class BlogPublishRequest {

    /** Halo 分类展示名 */
    private List<String> categoryNames;

    /** Halo 标签展示名；不存在则在 Halo 创建 */
    private List<String> tagNames;
}
