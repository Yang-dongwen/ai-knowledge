package com.dwcode.okxbot.kb.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ShareStatusResponse {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long noteId;

    private boolean enabled;
    private String shareToken;
    /** 前端可直接复制的相对路径，如 /s/xxxxx */
    private String sharePath;
    private LocalDateTime enabledAt;
}
