package com.dwcode.okxbot.aigen.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 内置模板（Phase 0 静态配置，后续可入库）。
 */
@Data
@Builder
public class AigenTemplateResponse {
    private String id;
    private String name;
    private String description;
    private String compositionId;
    private List<String> aspectRatios;
    private Integer defaultDurationSec;
    private Integer minDurationSec;
    private Integer maxDurationSec;
}
