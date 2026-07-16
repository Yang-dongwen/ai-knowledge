package com.dwcode.okxbot.aigen.adapter.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AiServices 声明式接口：输出分镜 JSON 字符串。
 * <p>
 * 故意返回 String 而非 {@code StoryboardDto}：SceneProps 有宽松反序列化，
 * 走业务侧 ObjectMapper 更稳（见 Phase B 设计）。
 */
public interface StoryboardJsonPlanner {

    @SystemMessage("{{systemPrompt}}")
    @UserMessage("{{userPrompt}}")
    String planJson(@V("systemPrompt") String systemPrompt, @V("userPrompt") String userPrompt);
}
