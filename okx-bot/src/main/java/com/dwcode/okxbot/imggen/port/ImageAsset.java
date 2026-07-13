package com.dwcode.okxbot.imggen.port;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImageAsset {
    private int index;
    /** 相对 workDir，如 outputs/img-01.png */
    private String relativePath;
    private Integer width;
    private Integer height;
    private Long seed;
}
