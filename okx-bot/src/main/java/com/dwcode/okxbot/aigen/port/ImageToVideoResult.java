package com.dwcode.okxbot.aigen.port;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImageToVideoResult {
    /** 相对 workDir，如 assets/visual/shot-1.mp4 */
    private String relativePath;
    /** kinetic | nvidia-svd | none */
    private String provider;
    private long latencyMs;
    private String errorMessage;

    public boolean isSuccess() {
        return relativePath != null && !relativePath.isBlank();
    }
}
