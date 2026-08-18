package com.dwcode.okxbot.horizon.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class HorizonDigestBrief {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long noteId;

    private String title;
    private String date;
    private String lang;
    private String snippet;
    private String haloPermalink;
    private LocalDateTime updatedAt;
}
