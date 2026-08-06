package com.dwcode.okxbot.imggen.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 文生图模块配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "imggen")
public class ImgGenProperties {

    private boolean enabled = true;
    private String workDir = "./data/imggen";
    private int maxConcurrentTasks = 2;
    /** 单用户进行中 + 排队上限 */
    private int maxConcurrentTasksPerUser = 2;
    private boolean mockPipeline = false;
    private long mockStepDelayMs = 400;
    private boolean cleanupOnDelete = true;
    private int maxN = 4;

    private Steps steps = new Steps();
    private PromptEnhance promptEnhance = new PromptEnhance();
    private Flux flux = new Flux();

    @Data
    public static class Steps {
        /** off | real | mock */
        private String enhance = "off";
        /** real | mock */
        private String generate = "real";
    }

    @Data
    public static class PromptEnhance {
        private boolean fallbackOnError = true;
        private String provider;
        private String model;
    }

    @Data
    public static class Flux {
        /** 复用 ai.providers 的 key，默认 nvidia */
        private String providerKey = "nvidia";
        /**
         * 兼容旧配置：未配置 models 列表时使用。
         */
        private String invokeUrl =
                "https://ai.api.nvidia.com/v1/genai/black-forest-labs/flux.1-dev";
        private String modelPath = "black-forest-labs/flux.1-dev";
        private int defaultSteps = 28;
        private int timeoutSeconds = 300;
        /** 默认生图模型 id（对应 models[].id） */
        private String defaultModel = "black-forest-labs/flux.1-dev";
        /** 可选生图模型目录 */
        private List<ImageModelDef> models = new ArrayList<>();
    }

    /**
     * 单个生图模型定义（yml 配置，供前端切换）。
     */
    @Data
    public static class ImageModelDef {
        /** 模型 ID，写入任务 model 字段 */
        private String id;
        /** 展示名 */
        private String name;
        /** NVIDIA GenAI invoke URL */
        private String invokeUrl;
        /** 未指定 steps 时的默认步数 */
        private int defaultSteps = 4;
        /** 允许的最大步数 */
        private int maxSteps = 50;
        /** 是否启用 */
        private boolean enabled = true;
        /** 备注 */
        private String description = "";
    }

    /**
     * 解析生图模型：优先 models 列表，否则退回 modelPath + invokeUrl。
     */
    public ImageModelDef resolveImageModel(String modelId) {
        List<ImageModelDef> catalog = listEnabledImageModels();
        if (modelId != null && !modelId.isBlank()) {
            for (ImageModelDef m : catalog) {
                if (modelId.equals(m.getId())) {
                    return m;
                }
            }
            throw new IllegalArgumentException("不支持的生图模型: " + modelId);
        }
        if (!catalog.isEmpty()) {
            String def = flux.getDefaultModel();
            if (def != null && !def.isBlank()) {
                for (ImageModelDef m : catalog) {
                    if (def.equals(m.getId())) {
                        return m;
                    }
                }
            }
            return catalog.get(0);
        }
        // 兼容仅配置了 model-path / invoke-url 的旧写法
        ImageModelDef fallback = new ImageModelDef();
        fallback.setId(flux.getModelPath() != null ? flux.getModelPath()
                : "black-forest-labs/flux.1-schnell");
        fallback.setName(fallback.getId());
        fallback.setInvokeUrl(flux.getInvokeUrl());
        fallback.setDefaultSteps(flux.getDefaultSteps());
        fallback.setMaxSteps(Math.max(flux.getDefaultSteps(), 50));
        fallback.setEnabled(true);
        return fallback;
    }

    public List<ImageModelDef> listEnabledImageModels() {
        List<ImageModelDef> out = new ArrayList<>();
        if (flux.getModels() != null) {
            for (ImageModelDef m : flux.getModels()) {
                if (m != null && m.isEnabled() && m.getId() != null && !m.getId().isBlank()) {
                    out.add(m);
                }
            }
        }
        return out;
    }
}
