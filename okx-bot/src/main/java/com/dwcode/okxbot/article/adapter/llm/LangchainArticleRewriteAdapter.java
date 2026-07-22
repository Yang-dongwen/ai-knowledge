package com.dwcode.okxbot.article.adapter.llm;

import com.dwcode.okxbot.article.config.ArticleProperties;
import com.dwcode.okxbot.article.enums.ArticleErrorCode;
import com.dwcode.okxbot.article.port.ArticleCoreResult;
import com.dwcode.okxbot.article.port.ArticleRewriteCommand;
import com.dwcode.okxbot.article.port.ArticleRewriteLlmPort;
import com.dwcode.okxbot.article.port.ArticleRewriteResult;
import com.dwcode.okxbot.article.port.MainTextDocument;
import com.dwcode.okxbot.article.service.ArticlePromptLoader;
import com.dwcode.okxbot.common.ai.LlmCallOptions;
import com.dwcode.okxbot.common.ai.LlmChatClient;
import com.dwcode.okxbot.common.ai.LlmContentHelper;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文章多形态二创：经 {@link LlmChatClient}（默认 LangChain4j）。
 */
@Slf4j
@RequiredArgsConstructor
public class LangchainArticleRewriteAdapter implements ArticleRewriteLlmPort {

    private final LlmChatClient llmChatClient;
    private final ArticleProperties properties;
    private final ArticlePromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    @Override
    public ArticleRewriteResult rewrite(ArticleCoreResult core, MainTextDocument doc, ArticleRewriteCommand cmd) {
        if (core == null || core.getRawJson() == null || core.getRawJson().isBlank()) {
            throw new BusinessException(400, ArticleErrorCode.LLM_REWRITE_FAILED + ": 缺少 CORE 结果");
        }
        List<String> variants = cmd.getVariants();
        if (variants == null || variants.isEmpty()) {
            variants = properties.getRewrite().getDefaultVariants();
        }
        String variantsCsv = variants.stream().map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.joining(","));

        String mainBrief = "";
        if (doc != null && doc.getMainText() != null) {
            String m = doc.getMainText();
            mainBrief = m.length() > 2500 ? m.substring(0, 2500) + "…" : m;
        }

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("variants", variantsCsv);
        vars.put("language", cmd.getLanguage() != null ? cmd.getLanguage() : "zh");
        vars.put("titleHint", cmd.getTitleHint() != null ? cmd.getTitleHint()
                : (core.getTitle() != null ? core.getTitle() : ""));
        vars.put("coreJson", core.getRawJson());
        vars.put("mainTextBrief", mainBrief);

        String system = promptLoader.load("rewrite_system.txt");
        if (system.isBlank()) {
            system = "你是新媒体编辑，只输出合法 JSON。";
        }
        String user = promptLoader.render("rewrite_user_template.txt", vars);
        if (user.isBlank()) {
            user = "根据 core 生成 rewriteVariants: " + variantsCsv + "\n" + core.getRawJson();
        }

        LlmCallOptions opts = LlmCallOptions.builder()
                .temperature(properties.getLlm().getRewriteTemperature())
                .maxTokens(4096)
                .maxRetries(2)
                .timeoutSeconds(180)
                .responseFormat(properties.getLlm().isJsonObjectEnabled() ? "json_object" : null)
                .build();

        String json;
        try {
            json = chatJson(system, user, cmd.getLlmProvider(), cmd.getLlmModel(), opts);
        } catch (Exception e) {
            throw new BusinessException(500, ArticleErrorCode.LLM_REWRITE_FAILED + ": " + e.getMessage());
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new BusinessException(500, ArticleErrorCode.LLM_REWRITE_FAILED + ": JSON 非法");
        }

        // 兼容 rewriteVariants 或 variants
        JsonNode arr = root.path("rewriteVariants");
        if (!arr.isArray() || arr.isEmpty()) {
            arr = root.path("variants");
        }
        int count = 0;
        if (arr.isArray()) {
            for (JsonNode v : arr) {
                if (v.path("content").asText("").trim().length() > 0) {
                    count++;
                }
            }
        }
        if (count == 0) {
            throw new BusinessException(500, ArticleErrorCode.LLM_REWRITE_FAILED + ": 无有效 variant content");
        }

        // 规范化字段名
        String outJson = json;
        try {
            if (!root.has("rewriteVariants") && root.has("variants")) {
                var on = objectMapper.createObjectNode();
                on.set("rewriteVariants", root.get("variants"));
                outJson = objectMapper.writeValueAsString(on);
            } else {
                outJson = objectMapper.writeValueAsString(root);
            }
        } catch (Exception ignored) {
            // keep raw
        }

        return ArticleRewriteResult.builder()
                .rawJson(outJson)
                .variantCount(count)
                .build();
    }

    private String chatJson(String system, String user, String provider, String model, LlmCallOptions opts) {
        try {
            String raw = llmChatClient.chat(system, user, provider, model, opts);
            return LlmContentHelper.extractJsonObject(raw);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (opts.getResponseFormat() != null
                    && (msg.contains("response_format") || msg.contains("json_object")
                    || msg.contains("unsupported"))) {
                log.warn("rewrite response_format 降级: {}", e.getMessage());
                LlmCallOptions plain = LlmCallOptions.builder()
                        .temperature(opts.getTemperature())
                        .maxTokens(opts.getMaxTokens())
                        .maxRetries(opts.getMaxRetries())
                        .timeoutSeconds(opts.getTimeoutSeconds())
                        .responseFormat(null)
                        .build();
                String raw = llmChatClient.chat(system, user, provider, model, plain);
                return LlmContentHelper.extractJsonObject(raw);
            }
            throw e instanceof RuntimeException re ? re : new BusinessException(500, e.getMessage());
        }
    }
}
