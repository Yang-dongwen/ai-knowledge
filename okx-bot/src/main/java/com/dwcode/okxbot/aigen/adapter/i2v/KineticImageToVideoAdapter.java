package com.dwcode.okxbot.aigen.adapter.i2v;

import com.dwcode.okxbot.aigen.port.ImageToVideoCommand;
import com.dwcode.okxbot.aigen.port.ImageToVideoPort;
import com.dwcode.okxbot.aigen.port.ImageToVideoResult;
import com.dwcode.okxbot.aigen.service.KineticClipService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 本地 FFmpeg zoompan 图生视频（默认稳定路径）。
 */
@Slf4j
@RequiredArgsConstructor
public class KineticImageToVideoAdapter implements ImageToVideoPort {

    private final KineticClipService kineticClipService;

    @Override
    public ImageToVideoResult convert(ImageToVideoCommand command) {
        long t0 = System.currentTimeMillis();
        if (command == null || command.getStillImage() == null) {
            return ImageToVideoResult.builder()
                    .provider("kinetic")
                    .errorMessage("stillImage 为空")
                    .latencyMs(0)
                    .build();
        }
        try {
            String rel = kineticClipService.generateClip(
                    command.getWorkDir(),
                    command.getStillImage(),
                    command.getShot(),
                    command.getWidth(),
                    command.getHeight(),
                    command.getSeedIndex()
            );
            return ImageToVideoResult.builder()
                    .relativePath(rel)
                    .provider("kinetic")
                    .latencyMs(System.currentTimeMillis() - t0)
                    .errorMessage(rel == null ? "ffmpeg 生成失败" : null)
                    .build();
        } catch (Exception e) {
            log.warn("kinetic i2v 异常: {}", e.getMessage());
            return ImageToVideoResult.builder()
                    .provider("kinetic")
                    .latencyMs(System.currentTimeMillis() - t0)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }
}
