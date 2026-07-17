package com.dwcode.okxbot.imggen.adapter.llm;

import com.dwcode.okxbot.imggen.port.PromptEnhancePort;
import com.dwcode.okxbot.common.ai.LlmChatClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 用现有 LlmChatClient 润色文生图 prompt。
 * 后续可替换为 LangChain4j ChatModel / AiServices，业务侧仍只依赖 {@link PromptEnhancePort}。
 */
@Slf4j
@RequiredArgsConstructor
public class LlmPromptEnhanceAdapter implements PromptEnhancePort {

    /**
     * 润色后写回用户输入框供确认：保持用户原语言，不强制英文化。
     */
    private static final String SYSTEM = """
            你是一位文生图（FLUX / Stable Diffusion 等）提示词润色专家。
            请把用户的创作想法改写成一条更清晰、更适合出图的提示词。

            规则：
            1. 必须与用户输入使用相同语言（用户写中文则输出中文，写英文则输出英文，勿擅自翻译成另一种语言）。
            2. 只输出润色后的提示词正文：不要引号、不要 markdown、不要解释或前后缀说明。
            3. 保留用户核心主体与意图，可补充风格、光影、构图、质感等有助于出图的细节，但不要偏离原意。
            4. 控制在约 200 字以内（或英文约 120 词以内）。
            5. 不要加入 NSFW 内容。
            """;

    private final LlmChatClient llmChatClient;

    @Override
    public String enhance(String originalPrompt, String languageHint, String llmProvider, String llmModel)
            throws Exception {
        String user = "请润色以下创作提示词：\n" + originalPrompt;
        if (languageHint != null && !languageHint.isBlank()) {
            user += "\n（语言偏好提示：" + languageHint + "，仅在与用户原文语言一致时参考）";
        }
        String out = llmChatClient.chat(SYSTEM, user, llmProvider, llmModel);
        if (out == null || out.isBlank()) {
            throw new IllegalStateException("LLM 润色返回空");
        }
        String cleaned = out.trim();
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        // 去掉常见「润色后：」类前缀
        cleaned = cleaned
                .replaceFirst("(?i)^(润色后(的)?(提示词)?|优化后|prompt)\\s*[:：]\\s*", "")
                .trim();
        log.info("Prompt 润色完成: inLen={} outLen={}", originalPrompt.length(), cleaned.length());
        return cleaned;
    }
}
