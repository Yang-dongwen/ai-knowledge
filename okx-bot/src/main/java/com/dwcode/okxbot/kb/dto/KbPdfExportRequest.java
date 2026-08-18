package com.dwcode.okxbot.kb.dto;

import lombok.Data;

@Data
public class KbPdfExportRequest {

    /** 文件名 / 文档标题 */
    private String title;

    /**
     * 已渲染的正文 HTML（#write 内部）。有则优先用当前编辑器内容；
     * 空则按已保存笔记渲染。
     */
    private String html;
}
