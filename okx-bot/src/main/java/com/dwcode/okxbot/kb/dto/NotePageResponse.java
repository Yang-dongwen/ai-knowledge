package com.dwcode.okxbot.kb.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class NotePageResponse {
    private List<NoteResponse> items;
    private long total;
    private int page;
    private int size;
}
