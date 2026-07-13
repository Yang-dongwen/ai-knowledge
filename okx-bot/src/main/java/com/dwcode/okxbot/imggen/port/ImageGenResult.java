package com.dwcode.okxbot.imggen.port;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ImageGenResult {
    private List<ImageAsset> images;
    private long providerLatencyMs;
    private String providerRequestId;
    private String rawMetaJson;
}
