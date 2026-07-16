package com.dwcode.okxbot.aigen.port;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DirectorCommand {
    private String prompt;
    private String language;
    private String aspectRatio;
    private int targetDurationSec;
    private String stylePreset;
    private String audioMode;
    private String llmProvider;
    private String llmModel;
    private String titleHint;
}
