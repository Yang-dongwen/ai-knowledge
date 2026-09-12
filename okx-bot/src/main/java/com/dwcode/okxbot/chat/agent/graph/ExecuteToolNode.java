package com.dwcode.okxbot.chat.agent.graph;

import com.dwcode.okxbot.chat.agent.ToolContext;
import com.dwcode.okxbot.chat.agent.ToolExecutionService;
import com.dwcode.okxbot.chat.agent.ToolResult;
import lombok.RequiredArgsConstructor;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ExecuteToolNode implements NodeAction<ChatAgentState> {

    private final ToolExecutionService toolExecutionService;

    @Override
    public Map<String, Object> apply(ChatAgentState state) {
        AgentTurnScope.throwIfCancelled();
        AgentTurnScope.emit("tool_running");
        ToolContext ctx = ToolContext.builder()
                .userId(state.userId())
                .conversationId(state.conversationId())
                .streamId(state.streamId())
                .build();
        ToolResult result = toolExecutionService.execute(state.tool(), ctx, state.args());
        boolean needsConfirm = result != null
                && "PENDING_CONFIRM".equals(result.getCode())
                && result.getUi() != null
                && "confirm".equals(String.valueOf(result.getUi().get("type")));
        Map<String, Object> out = new HashMap<>();
        out.put(ChatAgentState.TOOL_RESULT, result);
        out.put(ChatAgentState.NEEDS_CONFIRM, needsConfirm);
        out.put(ChatAgentState.USED_TOOL, true);
        if (needsConfirm) {
            String text = result.getMessage() != null ? result.getMessage() : "已准备操作，请确认后执行。";
            out.put(ChatAgentState.ASSISTANT, text);
            out.put(ChatAgentState.ROUTE, "confirm");
        } else if (result != null && !result.isOk()) {
            out.put(ChatAgentState.ASSISTANT, result.getMessage() != null ? result.getMessage() : "工具执行失败，请稍后重试。");
            out.put(ChatAgentState.ROUTE, "fail");
        } else {
            out.put(ChatAgentState.ROUTE, "summarize");
        }
        return out;
    }
}
