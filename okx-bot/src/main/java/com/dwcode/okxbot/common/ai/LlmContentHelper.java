package com.dwcode.okxbot.common.ai;

import com.dwcode.okxbot.common.exception.BusinessException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 LangChain4j / 原始文本抽取助手内容，兼容 reasoning 与 markdown 围栏 JSON。
 */
public final class LlmContentHelper {

    private static final Pattern JSON_BLOCK = Pattern.compile("(?s)```(?:json)?\\s*(\\{.*?})\\s*```");

    private LlmContentHelper() {
    }

    /**
     * 从 ChatResponse 取文本：优先 text，空则 thinking（returnThinking 时映射 reasoning）。
     */
    public static String extractText(ChatResponse response) {
        if (response == null || response.aiMessage() == null) {
            throw new BusinessException("LLM 返回为空");
        }
        return extractText(response.aiMessage());
    }

    public static String extractText(AiMessage aiMessage) {
        if (aiMessage == null) {
            throw new BusinessException("LLM 返回为空");
        }
        String text = aiMessage.text();
        if (text != null && !text.isBlank()) {
            return text;
        }
        // DeepSeek / 部分推理模型：正文在 thinking
        String thinking = aiMessage.thinking();
        if (thinking != null && !thinking.isBlank()) {
            return thinking;
        }
        throw new BusinessException("LLM 返回空内容");
    }

    /**
     * 从可能带 markdown 代码围栏的文本中提取 JSON 对象字符串。
     */
    public static String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("LLM 返回为空");
        }
        String t = raw.trim();
        Matcher m = JSON_BLOCK.matcher(t);
        if (m.find()) {
            return m.group(1);
        }
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                t = t.substring(firstNl + 1, lastFence).trim();
            }
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return t.substring(start, end + 1);
        }
        throw new BusinessException("无法从 LLM 响应解析 JSON 对象");
    }

    /**
     * 宽松提取：找不到对象时返回原文（摘要解析等可再兜底）。
     */
    public static String extractJsonObjectOrRaw(String raw) {
        try {
            return extractJsonObject(raw);
        } catch (BusinessException e) {
            return raw == null ? "" : raw.trim();
        }
    }

    public static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
