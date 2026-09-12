package com.dwcode.okxbot.chat.agent.graph;

import com.dwcode.okxbot.chat.agent.ToolResult;
import com.dwcode.okxbot.chat.config.AgentProperties;
import com.dwcode.okxbot.chat.stream.StreamCancelledException;
import com.dwcode.okxbot.common.ai.LlmCallOptions;
import com.dwcode.okxbot.common.ai.LlmChatGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SummarizeNode implements NodeAction<ChatAgentState> {

    private final AgentProperties agentProperties;
    private final LlmChatGateway llmChatGateway;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<String, Object> apply(ChatAgentState state) {
        AgentTurnScope.throwIfCancelled();
        AgentTurnScope.emit("summarizing");
        ToolResult toolResult = state.toolResult();
        String dataJson;
        try {
            dataJson = objectMapper.writeValueAsString(
                    Map.of(
                            "tool", state.tool() == null ? "" : state.tool(),
                            "ok", toolResult != null && toolResult.isOk(),
                            "message", toolResult != null && toolResult.getMessage() != null ? toolResult.getMessage() : "",
                            "data", toolResult != null && toolResult.getData() != null ? toolResult.getData() : Map.of()
                    ));
            if (dataJson.length() > 6000) {
                dataJson = dataJson.substring(0, 6000) + "…(truncated)";
            }
        } catch (Exception e) {
            dataJson = toolResult != null && toolResult.getMessage() != null ? toolResult.getMessage() : "";
        }

        String system = """
                你是 AI 工具台助手。根据工具返回的真实数据回答用户，用简洁中文。
                禁止编造任务 ID、状态或结果。若工具失败，如实说明原因并给出下一步建议。
                """.stripIndent().trim();
        String user = "用户问题：\n" + state.userMessage() + "\n\n工具 " + state.tool() + " 返回：\n" + dataJson;

        LlmCallOptions opts = LlmCallOptions.builder()
                .temperature(0.4)
                .maxTokens(agentProperties.getSummaryMaxTokens())
                .maxRetries(0)
                .timeoutSeconds(60)
                .build();
        try {
            AgentTurnScope.throwIfCancelled();
            String summary = llmChatGateway.chatMessages(
                    List.of(
                            dev.langchain4j.data.message.SystemMessage.from(system),
                            dev.langchain4j.data.message.UserMessage.from(user)
                    ),
                    state.providerKey(),
                    state.modelId(),
                    opts
            );
            return Map.of(ChatAgentState.ASSISTANT, summary);
        } catch (StreamCancelledException e) {
            throw e;
        } catch (Exception e) {
            AgentTurnScope.throwIfCancelled();
            log.warn("工具结果总结失败，使用原始 message: {}", e.getMessage());
            String fallback = toolResult != null && toolResult.getMessage() != null
                    ? toolResult.getMessage()
                    : "工具已执行，但生成总结失败。你可在任务列表中查看详情。";
            return Map.of(ChatAgentState.ASSISTANT, fallback);
        }
    }
}
