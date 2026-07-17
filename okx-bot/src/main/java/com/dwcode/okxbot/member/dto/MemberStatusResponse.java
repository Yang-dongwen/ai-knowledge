package com.dwcode.okxbot.member.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MemberStatusResponse {
    private String role;
    private String roleLabel;
    private Boolean memberActive;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime memberExpireAt;
}
