package com.dwcode.okxbot.imggen.util;

/**
 * 生图协议：决定走哪个 Adapter / 请求体格式。
 */
public final class ImageProtocol {

    /** NVIDIA FLUX 等 GenAI 一模型一 URL：prompt/width/height/seed/steps */
    public static final String NVIDIA_FLUX = "nvidia-flux";
    /**
     * Qwen-Image：官方 Visual GenAI 默认走 OpenAI 兼容 Images
     * （POST /v1/images/generations），不是 /v1/genai/qwen/...
     */
    public static final String NVIDIA_QWEN = "nvidia-qwen";
    /** 显式 OpenAI 兼容 Images API */
    public static final String NVIDIA_OPENAI_IMAGES = "nvidia-openai-images";
    /** Qwen / Visual GenAI 原生 POST /v1/infer */
    public static final String NVIDIA_QWEN_INFER = "nvidia-qwen-infer";
    public static final String MOCK = "mock";

    private ImageProtocol() {
    }

    /**
     * 规范化协议；空则根据 modelId / invokeUrl 推断。
     */
    public static String resolve(String protocol, String modelId, String invokeUrl) {
        if (protocol != null && !protocol.isBlank()) {
            String p = protocol.trim().toLowerCase();
            return switch (p) {
                case "flux", "flux-genai", "nvidia-flux-genai" -> NVIDIA_FLUX;
                case "qwen", "qwen-genai", "nvidia-qwen-genai" -> NVIDIA_QWEN;
                case "qwen-infer", "infer", "nvidia-infer" -> NVIDIA_QWEN_INFER;
                case "openai", "openai-images", "images" -> NVIDIA_OPENAI_IMAGES;
                default -> p;
            };
        }
        String m = modelId == null ? "" : modelId.toLowerCase();
        String u = invokeUrl == null ? "" : invokeUrl.toLowerCase();
        if (u.contains("/v1/infer")) {
            return NVIDIA_QWEN_INFER;
        }
        if (u.contains("images/generations") || u.contains("/v1/images")) {
            return NVIDIA_OPENAI_IMAGES;
        }
        if (m.contains("qwen") || u.contains("qwen")) {
            return NVIDIA_QWEN;
        }
        return NVIDIA_FLUX;
    }
}
