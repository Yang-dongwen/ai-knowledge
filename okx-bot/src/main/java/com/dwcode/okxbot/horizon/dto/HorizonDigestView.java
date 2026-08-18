package com.dwcode.okxbot.horizon.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class HorizonDigestView {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long noteId;

    private String title;
    private String date;
    private String lang;
    private String markdown;
    private String snippet;
    private String haloPermalink;
    private LocalDateTime updatedAt;
}
