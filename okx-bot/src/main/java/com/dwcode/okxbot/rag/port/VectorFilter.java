package com.dwcode.okxbot.rag.port;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class VectorFilter {
    long userId;
    String sourceType;
    Long noteId;
    Long fileId;
}
