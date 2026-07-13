package com.dwcode.okxbot.imggen.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ImgGenTaskPageResponse {
    private List<ImgGenTaskResponse> items;
    private long total;
    private int page;
    private int size;
}
