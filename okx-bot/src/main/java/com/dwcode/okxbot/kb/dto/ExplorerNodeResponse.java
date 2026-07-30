package com.dwcode.okxbot.kb.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 目录树节点：folder（文件夹）或 note（文档）。
 */
@Data
@Builder
public class ExplorerNodeResponse {

    /** folder | note */
    private String type;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 文件夹名或文档标题 */
    private String name;

    /** 文件夹：父文件夹 id；文档：所在文件夹 id（可空=未归档） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    /** 文档：html | markdown */
    private String contentFormat;

    private boolean pinned;

    private String snippet;

    private LocalDateTime updatedAt;

    private Integer sortOrder;

    @Builder.Default
    private List<ExplorerNodeResponse> children = new ArrayList<>();
}
