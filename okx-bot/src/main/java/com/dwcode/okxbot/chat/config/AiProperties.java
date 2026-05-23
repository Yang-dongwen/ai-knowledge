package com.dwcode.okxbot.chat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 多供应商配置属性。
 *
 * 配置示例：
 * ai:
 *   providers:
 *     deepseek:
 *       name: DeepSeek
 *       base-url: https://api.deepseek.com/v1
 *       api-key: xxx
 *       models:
 *         - id: deepseek-chat
 *           name: DeepSeek Chat
 *   default-provider: deepseek
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** 供应商配置 Map，key 为供应商标识 */
    private LinkedHashMap<String, ProviderConfig> providers = new LinkedHashMap<>();

    /** 默认供应商标识 */
    private String defaultProvider;

    /** 上下文最大消息数 */
    private int maxContextMessages = 20;

    /**
     * 获取指定供应商配置。
     */
    public ProviderConfig getProvider(String key) {
        return providers.get(key);
    }

    /**
     * 获取默认供应商配置。
     */
    public ProviderConfig getDefaultProvider() {
        if (defaultProvider != null && providers.containsKey(defaultProvider)) {
            return providers.get(defaultProvider);
        }
        // 兜底：返回第一个有 apiKey 的供应商
        return getAllAvailableProviders().stream().findFirst().orElse(null);
    }

    /**
     * 获取所有可用供应商（apiKey 不为空的）。
     */
    public List<Map.Entry<String, ProviderConfig>> getAllAvailableProviders() {
        return providers.entrySet().stream()
                .filter(e -> e.getValue().getApiKey() != null && !e.getValue().getApiKey().isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 获取指定供应商的指定模型配置。
     */
    public ModelConfig getModel(String providerKey, String modelId) {
        ProviderConfig provider = getProvider(providerKey);
        if (provider == null || provider.getModels() == null) {
            return null;
        }
        return provider.getModels().stream()
                .filter(m -> m.getId().equals(modelId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 单个供应商配置。
     */
    @Data
    public static class ProviderConfig {
        /** 供应商显示名称 */
        private String name;
        /** API 基础地址 */
        private String baseUrl;
        /** API Key */
        private String apiKey;
        /** 可用模型列表 */
        private List<ModelConfig> models = new ArrayList<>();
    }

    /**
     * 模型配置。
     */
    @Data
    public static class ModelConfig {
        /** 模型 ID（发送给 API 的标识） */
        private String id;
        /** 模型显示名称 */
        private String name;
    }
}