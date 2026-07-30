package com.dwcode.okxbot.kb.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class TagResponse {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String name;

    /** 关联笔记数（未软删） */
    private long noteCount;

    private LocalDateTime createdAt;
}
