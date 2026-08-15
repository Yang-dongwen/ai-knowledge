package com.dwcode.okxbot.kb.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class BlogPublishOptionsResponse {

    private boolean published;

    private String permalink;

    private List<HaloTermResponse> categories;

    private List<HaloTermResponse> tags;

    /** 当前文章已选分类（展示名） */
    private List<String> selectedCategoryNames;

    /** 当前文章已选标签（展示名） */
    private List<String> selectedTagNames;

    /** 正文引用 + 绑定附件数 */
    private int mediaCount;
}
