package com.dwcode.okxbot.imggen.adapter.llm;

import com.dwcode.okxbot.imggen.port.PromptEnhancePort;
import com.dwcode.okxbot.video.client.LlmChatClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 用现有 LlmChatClient 润色文生图 prompt。
 * 后续可替换为 LangChain4j ChatModel / AiServices，业务侧仍只依赖 {@link PromptEnhancePort}。
 */
@Slf4j
@RequiredArgsConstructor
public class LlmPromptEnhanceAdapter implements PromptEnhancePort {

    private static final String SYSTEM = """
            You are an expert prompt engineer for FLUX / Stable Diffusion style image models.
            Rewrite the user's idea into a single vivid English image prompt.
            Rules:
            - Output ONLY the prompt text, no quotes, no markdown, no explanation.
            - Keep under 120 words.
            - Include subject, style, lighting, composition when helpful.
            - Do not add NSFW content.
            """;

    private final LlmChatClient llmChatClient;

    @Override
    public String enhance(String originalPrompt, String languageHint, String llmProvider, String llmModel)
            throws Exception {
        String user = "User idea:\n" + originalPrompt;
        if (languageHint != null && !languageHint.isBlank()) {
            user += "\nLanguage hint: " + languageHint;
        }
        String out = llmChatClient.chat(SYSTEM, user, llmProvider, llmModel);
        if (out == null || out.isBlank()) {
            throw new IllegalStateException("LLM 润色返回空");
        }
        String cleaned = out.trim();
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        log.info("Prompt 润色完成: inLen={} outLen={}", originalPrompt.length(), cleaned.length());
        return cleaned;
    }
}
