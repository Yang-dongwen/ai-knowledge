package com.dwcode.okxbot.aigen.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AigenTaskPageResponse {
    private List<AigenTaskResponse> items;
    private long total;
    private int page;
    private int size;
}
