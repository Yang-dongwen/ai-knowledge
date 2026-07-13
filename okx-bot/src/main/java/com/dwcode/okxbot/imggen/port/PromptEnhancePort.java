package com.dwcode.okxbot.imggen.port;

/**
 * Prompt 润色端口（文本侧，可后续换 LangChain4j ChatModel）。
 */
public interface PromptEnhancePort {
    String enhance(String originalPrompt, String languageHint, String llmProvider, String llmModel)
            throws Exception;
}
