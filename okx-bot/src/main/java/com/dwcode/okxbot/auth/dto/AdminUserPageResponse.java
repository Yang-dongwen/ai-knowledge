package com.dwcode.okxbot.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminUserPageResponse {
    private List<AuthUserResponse> items;
    private long total;
    private int page;
    private int size;
}
