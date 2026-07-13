package com.dwcode.okxbot.aigen.port;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PlanCommand {
    private String prompt;
    private String templateId;
    private String language;
    private String aspectRatio;
    private int targetDurationSec;
    private String llmProvider;
    private String llmModel;
    private String titleHint;
}
