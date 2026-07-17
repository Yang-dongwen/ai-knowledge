package com.dwcode.okxbot.aigen.adapter.i2v;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.port.ImageToVideoCommand;
import com.dwcode.okxbot.aigen.port.ImageToVideoPort;
import com.dwcode.okxbot.aigen.port.ImageToVideoResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

/**
 * 按 aigen.visual.i2v-mode 路由图生视频实现。
 */
@Slf4j
@RequiredArgsConstructor
public class CompositeImageToVideoAdapter implements ImageToVideoPort {

    private final AigenProperties aigenProperties;
    private final KineticImageToVideoAdapter kinetic;
    private final NvidiaSvdImageToVideoAdapter nvidiaSvd;

    @Override
    public ImageToVideoResult convert(ImageToVideoCommand command) {
        AigenProperties.Visual v = aigenProperties.getVisual();
        if (v == null || !v.isKineticClips()) {
            return ImageToVideoResult.builder()
                    .provider("none")
                    .errorMessage("kineticClips/i2v 已关闭")
                    .build();
        }
        String mode = v.getI2vMode() != null
                ? v.getI2vMode().trim().toLowerCase(Locale.ROOT)
                : "kinetic";

        if ("off".equals(mode) || "none".equals(mode) || "false".equals(mode)) {
            return ImageToVideoResult.builder()
                    .provider("off")
                    .errorMessage("i2v-mode=off")
                    .build();
        }

        if ("nvidia-svd".equals(mode) || "svd".equals(mode)) {
            ImageToVideoResult r = nvidiaSvd.convert(command);
            if (r.isSuccess()) {
                return r;
            }
            if (v.isI2vFailOpenToKinetic()) {
                log.warn("nvidia-svd 失败，回退 kinetic: {}", r.getErrorMessage());
                return kinetic.convert(command);
            }
            return r;
        }

        if ("auto".equals(mode)) {
            ImageToVideoResult r = nvidiaSvd.convert(command);
            if (r.isSuccess()) {
                return r;
            }
            log.info("auto i2v：svd 不可用，使用 kinetic: {}", r.getErrorMessage());
            return kinetic.convert(command);
        }

        // 默认 kinetic
        return kinetic.convert(command);
    }
}
