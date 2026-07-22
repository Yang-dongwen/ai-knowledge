package com.dwcode.okxbot.article.port;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MainTextDocument {
    private String title;
    private String author;
    private String mainText;
    private String languageHint;
    private double qualityScore;
    private boolean truncated;
    private boolean unusable;
    private String unusableReason;
    private String source; // html | plain | paste
}
