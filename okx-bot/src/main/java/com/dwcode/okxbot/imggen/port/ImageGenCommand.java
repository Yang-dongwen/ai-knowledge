package com.dwcode.okxbot.imggen.port;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;

@Data
@Builder
public class ImageGenCommand {
    private String taskId;
    private String prompt;
    private String negativePrompt;
    /** 生图模型 ID（日志/落盘） */
    private String modelId;
    /** 供应商 key，用于取 api-key，默认 nvidia */
    private String providerKey;
    /** 完整 invoke URL（由任务模型解析，NVIDIA FLUX GenAI） */
    private String invokeUrl;
    private int width;
    private int height;
    private int steps;
    private int n;
    private Long seed;
    private Path workDir;
    private Path outputsDir;
}
