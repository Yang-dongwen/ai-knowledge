package com.dwcode.okxbot.kb.dto;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class ExplorerTreeResponse {

    /** 根层：根文件夹 + 未归档文档（混排：文件夹在前，文档在后） */
    @Builder.Default
    private List<ExplorerNodeResponse> roots = new ArrayList<>();

    private int folderCount;
    private int noteCount;
}
