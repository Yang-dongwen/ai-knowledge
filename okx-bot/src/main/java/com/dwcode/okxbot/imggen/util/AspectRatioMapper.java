package com.dwcode.okxbot.imggen.util;

import com.dwcode.okxbot.common.exception.BusinessException;

/**
 * 将产品比例映射到 NVIDIA FLUX 支持的分辨率。
 */
public final class AspectRatioMapper {

    private AspectRatioMapper() {
    }

    public record Size(int width, int height) {
    }

    public static Size map(String aspectRatio) {
        String ar = aspectRatio == null ? "1:1" : aspectRatio.trim();
        return switch (ar) {
            case "1:1" -> new Size(1024, 1024);
            case "16:9" -> new Size(1344, 768);
            case "9:16" -> new Size(768, 1344);
            case "4:3" -> new Size(1152, 896);
            case "3:4" -> new Size(896, 1152);
            default -> throw new BusinessException(400, "不支持的 aspectRatio: " + ar
                    + "，请用 1:1 / 16:9 / 9:16 / 4:3 / 3:4");
        };
    }
}
