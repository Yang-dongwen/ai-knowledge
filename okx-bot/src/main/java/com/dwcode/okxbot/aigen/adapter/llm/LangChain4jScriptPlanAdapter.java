package com.dwcode.okxbot.aigen.adapter.llm;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.domain.StoryboardDto;
import com.dwcode.okxbot.aigen.port.PlanCommand;
import com.dwcode.okxbot.aigen.port.ScriptPlanPort;
import com.dwcode.okxbot.aigen.service.StoryboardNormalizeService;
import com.dwcode.okxbot.aigen.service.StoryboardValidateService;
import com.dwcode.okxbot.aigen.service.TemplateRegistry;
import com.dwcode.okxbot.common.ai.ChatModelFactory;
import com.dwcode.okxbot.common.ai.LlmCallOptions;
import com.dwcode.okxbot.common.ai.LlmChatClient;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Phase B：LangChain4j AiServices + JSON Object 模式的分镜规划适配器。
 * <p>
 * 仍实现 {@link ScriptPlanPort}；Pipeline 无感知。
 * 流程：AiServices 出 JSON 字符串 → ObjectMapper → Normalize → Validate → 可选 repair。
 */
@Slf4j
@RequiredArgsConstructor
public class LangChain4jScriptPlanAdapter implements ScriptPlanPort {

    private final ChatModelFactory chatModelFactory;
    private final LlmChatClient llmChatClient;
    private final ObjectMapper objectMapper;
    private final StoryboardValidateService validateService;
    private final StoryboardNormalizeService normalizeService;
    private final TemplateRegistry templateRegistry;
    private final AigenProperties aigenProperties;

    @Override
    public StoryboardDto plan(PlanCommand command) {
        var def = templateRegistry.require(command.getTemplateId());
        String system = ScriptPlanSupport.buildSystemPrompt(command, def.allowedSceneTypes().toString());
        String user = "用户主题/提示词：\n" + command.getPrompt();

        String provider = command.getLlmProvider();
        String model = command.getLlmModel();
        LlmCallOptions options = buildPlanOptions();

        if (aigenProperties.getLlm().isLogPrompts()) {
            log.info("aigen plan systemPrompt len={}, userPrompt len={}", system.length(), user.length());
        }

        try {
            String raw = invokePlanner(system, user, provider, model, options);
            StoryboardDto dto = ScriptPlanSupport.parseStoryboard(objectMapper, raw);
            dto = normalizeService.normalize(
                    dto,
                    command.getTemplateId(),
                    command.getAspectRatio(),
                    command.getTargetDurationSec(),
                    command.getLanguage(),
                    command.getTitleHint()
            );
            List<String> errors = validateService.validate(
                    dto, command.getTemplateId(), command.getAspectRatio(), command.getTargetDurationSec());
            if (!errors.isEmpty()) {
                int maxRepair = Math.max(0, aigenProperties.getLlm().getMaxRepairAttempts());
                if (maxRepair > 0) {
                    log.warn("分镜校验失败，尝试 repair(langchain4j): {}", errors);
                    String repairUser = "以下 JSON 不合法，错误: " + errors
                            + "\n请输出修复后的完整 JSON：\n" + objectMapper.writeValueAsString(dto);
                    String repaired = invokePlanner(
                            "你是 JSON 修复器，只输出合法分镜 JSON，不要 markdown。",
                            repairUser, provider, model, options);
                    dto = ScriptPlanSupport.parseStoryboard(objectMapper, repaired);
                    dto = normalizeService.normalize(
                            dto,
                            command.getTemplateId(),
                            command.getAspectRatio(),
                            command.getTargetDurationSec(),
                            command.getLanguage(),
                            command.getTitleHint()
                    );
                    errors = validateService.validate(
                            dto, command.getTemplateId(), command.getAspectRatio(), command.getTargetDurationSec());
                }
            }
            if (!errors.isEmpty()) {
                throw new BusinessException("分镜校验失败: " + String.join("; ", errors));
            }
            return dto;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("LangChain4j 规划失败: {}", e.getMessage());
            throw new BusinessException("规划失败: " + e.getMessage());
        }
    }

    /**
     * 优先 AiServices；失败时回退 {@link LlmChatClient}（同 options）。
     */
    private String invokePlanner(String system,
                                 String user,
                                 String provider,
                                 String model,
                                 LlmCallOptions options) {
        try {
            ChatModel chatModel = chatModelFactory.create(provider, model, options);
            StoryboardJsonPlanner planner = AiServices.builder(StoryboardJsonPlanner.class)
                    .chatModel(chatModel)
                    .build();
            String raw = planner.planJson(system, user);
            if (raw == null || raw.isBlank()) {
                throw new BusinessException("AiServices 返回空分镜");
            }
            return raw;
        } catch (Exception e) {
            log.warn("AiServices 规划调用失败，回退 LlmChatClient: {}", e.getMessage());
            return llmChatClient.chat(system, user, provider, model, options);
        }
    }

    private LlmCallOptions buildPlanOptions() {
        AigenProperties.Llm llm = aigenProperties.getLlm();
        LlmCallOptions.LlmCallOptionsBuilder b = LlmCallOptions.builder()
                .temperature(llm.getTemperature())
                .maxTokens(llm.getMaxTokens())
                .maxRetries(llm.getMaxRetries())
                .timeoutSeconds(Math.max(30, llm.getTimeoutSeconds()));
        if (wantJsonObjectMode(llm.getStructuredMode())) {
            b.responseFormat("json_object");
        }
        return b.build();
    }

    /**
     * structured-mode: auto | json | off
     */
    static boolean wantJsonObjectMode(String structuredMode) {
        if (structuredMode == null || structuredMode.isBlank()) {
            return true;
        }
        String m = structuredMode.trim().toLowerCase();
        if ("off".equals(m) || "false".equals(m) || "none".equals(m)) {
            return false;
        }
        // auto / json / true
        return true;
    }
}
