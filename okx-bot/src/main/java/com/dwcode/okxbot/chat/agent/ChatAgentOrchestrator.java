package com.dwcode.okxbot.chat.agent;

import com.dwcode.okxbot.chat.agent.graph.AgentGraphFactory;
import com.dwcode.okxbot.chat.agent.graph.AgentTurnScope;
import com.dwcode.okxbot.chat.agent.graph.ChatAgentState;
import com.dwcode.okxbot.chat.config.AgentProperties;
import com.dwcode.okxbot.chat.stream.StreamCancelledException;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Agent 一轮：LangGraph4j 图 retrieve → decide → execute/summarize。
 * WRITE 只出确认草案，真正执行仍走 {@link AgentConfirmService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAgentOrchestrator {

    private final AgentProperties agentProperties;
    private final AgentGraphFactory agentGraphFactory;

    @Value
    public static class AgentTurnResult {
        String assistantText;
        String toolName;
        ToolResult toolResult;
        boolean usedTool;
        boolean needsConfirm;
    }

    public AgentTurnResult runTurn(String userMessage,
                                   String historyDigest,
                                   String providerKey,
                                   String modelId,
                                   ToolContext toolContext,
                                   Supplier<Boolean> cancelCheck,
                                   Consumer<String> phaseCallback) {
        if (!agentProperties.isEnabled()) {
            return new AgentTurnResult(null, null, null, false, false);
        }

        Map<String, Object> init = new HashMap<>();
        init.put(ChatAgentState.USER_MESSAGE, userMessage == null ? "" : userMessage);
        init.put(ChatAgentState.HISTORY, historyDigest == null ? "" : historyDigest);
        init.put(ChatAgentState.USER_ID, toolContext != null ? toolContext.getUserId() : null);
        init.put(ChatAgentState.CONVERSATION_ID, toolContext != null ? toolContext.getConversationId() : null);
        init.put(ChatAgentState.STREAM_ID, toolContext != null ? toolContext.getStreamId() : "");
        init.put(ChatAgentState.PROVIDER, providerKey);
        init.put(ChatAgentState.MODEL, modelId);

        AgentTurnScope.open(cancelCheck, phaseCallback);
        try {
            Optional<ChatAgentState> last = agentGraphFactory.compiled().invoke(init);
            if (last.isEmpty()) {
                return new AgentTurnResult(null, null, null, false, false);
            }
            ChatAgentState state = last.get();
            return new AgentTurnResult(
                    state.assistantText(),
                    state.tool(),
                    state.toolResult(),
                    state.usedTool(),
                    state.needsConfirm());
        } catch (StreamCancelledException e) {
            throw e;
        } catch (Exception e) {
            if (cancelCheck != null && Boolean.TRUE.equals(cancelCheck.get())) {
                throw new StreamCancelledException("");
            }
            log.warn("Agent 图执行失败，回退纯聊天: {}", e.getMessage());
            return new AgentTurnResult(null, null, null, false, false);
        } finally {
            AgentTurnScope.close();
        }
    }

    public AgentTurnResult runTurn(String userMessage,
                                   String historyDigest,
                                   String providerKey,
                                   String modelId,
                                   ToolContext toolContext) {
        return runTurn(userMessage, historyDigest, providerKey, modelId, toolContext, () -> false, null);
    }
}
