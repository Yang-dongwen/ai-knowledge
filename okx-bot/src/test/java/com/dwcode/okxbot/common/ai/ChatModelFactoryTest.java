package com.dwcode.okxbot.common.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatModelFactoryTest {

    @Test
    void normalizeOpenAiBaseUrl_stripsChatCompletionsAndTrailingSlash() {
        assertEquals(
                "https://integrate.api.nvidia.com/v1",
                ChatModelFactory.normalizeOpenAiBaseUrl("https://integrate.api.nvidia.com/v1/")
        );
        assertEquals(
                "https://integrate.api.nvidia.com/v1",
                ChatModelFactory.normalizeOpenAiBaseUrl("https://integrate.api.nvidia.com/v1/chat/completions")
        );
        assertEquals(
                "https://api.deepseek.com/v1",
                ChatModelFactory.normalizeOpenAiBaseUrl("https://api.deepseek.com")
        );
    }

    @Test
    void normalizeOpenAiBaseUrl_rejectsBlank() {
        assertThrows(Exception.class, () -> ChatModelFactory.normalizeOpenAiBaseUrl("  "));
    }
}
