package com.dwcode.okxbot.rag.port;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class VectorHit {
    String pointId;
    float score;
    long userId;
    String sourceType;
    long noteId;
    Long fileId;
    int chunkIndex;
    String title;
    String text;
    String kind;
    String fileName;
}
