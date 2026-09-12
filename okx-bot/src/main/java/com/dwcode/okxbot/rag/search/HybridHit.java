package com.dwcode.okxbot.rag.search;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class HybridHit {
    long noteId;
    Long fileId;
    String sourceType;
    String title;
    String snippet;
    String fileName;
    String kind;
    double score;
}
