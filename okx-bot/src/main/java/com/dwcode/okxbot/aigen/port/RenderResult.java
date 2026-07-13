package com.dwcode.okxbot.aigen.port;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RenderResult {
    private boolean success;
    private String outputAbsolutePath;
    private Long renderDurationMs;
    private String error;
}
