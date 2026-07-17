package com.dwcode.okxbot.aigen.port;

import com.dwcode.okxbot.aigen.domain.shot.ShotDto;
import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;

/**
 * 静图 → 短视频片段。
 */
@Data
@Builder
public class ImageToVideoCommand {
    private Path workDir;
    private Path stillImage;
    private ShotDto shot;
    private int width;
    private int height;
    private int seedIndex;
    /** 供应商 key（nvidia-svd 取 api-key） */
    private String providerKey;
}
