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
    /**
     * 协议：nvidia-flux | nvidia-qwen | nvidia-openai-images | mock
     * 空则按 modelId/url 推断
     */
    private String protocol;
    /** 完整 invoke URL（由任务模型解析） */
    private String invokeUrl;
    private int width;
    private int height;
    private int steps;
    private int n;
    private Long seed;
    private Path workDir;
    private Path outputsDir;
}
