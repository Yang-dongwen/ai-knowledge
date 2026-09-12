package com.dwcode.okxbot.rag.adapter.embedding;

import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.rag.port.EmbeddingPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Google AI Studio（Gemini Developer API）embedding。
 */
@Slf4j
public class GoogleAiStudioEmbeddingAdapter implements EmbeddingPort {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final AiProperties.EmbeddingConfig cfg;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public GoogleAiStudioEmbeddingAdapter(AiProperties.EmbeddingConfig cfg) {
        this.cfg = cfg;
        int timeout = Math.max(5, cfg.getTimeoutSeconds());
        this.http = new OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(timeout, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public boolean available() {
        return StringUtils.hasText(cfg.getApiKey());
    }

    @Override
    public int dimensions() {
        return cfg.getOutputDimensionality();
    }

    @Override
    public String modelId() {
        return cfg.getModel();
    }

    @Override
    public float[] embedQuery(String text) {
        return embedOne(text, cfg.getTaskTypeQuery());
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            out.add(embedOne(t, cfg.getTaskTypeDocument()));
        }
        return out;
    }

    private float[] embedOne(String text, String taskType) {
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(400, "embedding 文本为空");
        }
        String snippet = text.length() > 8000 ? text.substring(0, 8000) : text;
        int retries = Math.max(0, cfg.getMaxRetries());
        Exception last = null;
        for (int i = 0; i <= retries; i++) {
            try {
                return callEmbed(snippet, taskType);
            } catch (Exception e) {
                last = e;
                log.warn("Gemini embedding 失败 attempt={}/{}: {}", i + 1, retries + 1, e.getMessage());
            }
        }
        throw new BusinessException(502, "向量化失败: " + (last != null ? last.getMessage() : "unknown"));
    }

    private float[] callEmbed(String text, String taskType) throws IOException {
        String model = cfg.getModel();
        String base = trimSlash(cfg.getBaseUrl());
        String url = base + "/models/" + model + ":embedContent";

        Map<String, Object> body = new HashMap<>();
        body.put("model", "models/" + model);
        body.put("content", Map.of("parts", List.of(Map.of("text", text))));
        if (StringUtils.hasText(taskType)) {
            body.put("taskType", taskType);
        }
        if (cfg.getOutputDimensionality() > 0) {
            body.put("outputDimensionality", cfg.getOutputDimensionality());
        }

        Request request = new Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", cfg.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " " + truncate(resp, 300));
            }
            JsonNode root = mapper.readTree(resp);
            JsonNode values = root.path("embedding").path("values");
            if (!values.isArray() || values.isEmpty()) {
                throw new IOException("embedding.values 为空");
            }
            float[] vec = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vec[i] = (float) values.get(i).asDouble();
            }
            return vec;
        }
    }

    private static String trimSlash(String s) {
        if (s == null || s.isBlank()) {
            return "https://generativelanguage.googleapis.com/v1beta";
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n);
    }
}
