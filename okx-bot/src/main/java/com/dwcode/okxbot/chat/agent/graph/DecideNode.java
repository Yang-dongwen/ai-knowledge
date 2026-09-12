package com.dwcode.okxbot.chat.agent.graph;

import com.dwcode.okxbot.chat.agent.IntentJsonParser;
import com.dwcode.okxbot.chat.agent.ToolRegistry;
import com.dwcode.okxbot.chat.config.AgentProperties;
import com.dwcode.okxbot.common.ai.LlmCallOptions;
import com.dwcode.okxbot.common.ai.LlmChatGateway;
import com.dwcode.okxbot.rag.search.HybridHit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DecideNode implements NodeAction<ChatAgentState> {

    private final AgentProperties agentProperties;
    private final ToolRegistry toolRegistry;
    private final IntentJsonParser intentJsonParser;
    private final LlmChatGateway llmChatGateway;

    @Override
    public Map<String, Object> apply(ChatAgentState state) {
        AgentTurnScope.throwIfCancelled();
        AgentTurnScope.emit("deciding");

        String decisionSystem = buildDecisionSystemPrompt(state.retrieved());
        String decisionUser = buildDecisionUserPrompt(state.userMessage(), state.historyDigest(), state.retrieved());

        LlmCallOptions decisionOpts = LlmCallOptions.builder()
                .temperature(agentProperties.getDecisionTemperature())
                .maxTokens(agentProperties.getDecisionMaxTokens())
                .maxRetries(0)
                .timeoutSeconds(30)
                .build();

        String decisionRaw;
        try {
            decisionRaw = llmChatGateway.chatMessages(
                    List.of(
                            dev.langchain4j.data.message.SystemMessage.from(decisionSystem),
                            dev.langchain4j.data.message.UserMessage.from(decisionUser)
                    ),
                    state.providerKey(),
                    state.modelId(),
                    decisionOpts
            );
        } catch (Exception e) {
            AgentTurnScope.throwIfCancelled();
            log.warn("Agent 决策失败，回退纯聊天: {}", e.getMessage());
            return Map.of(
                    ChatAgentState.ROUTE, "fallback",
                    ChatAgentState.PARSED, false,
                    ChatAgentState.USED_TOOL, false
            );
        }

        IntentJsonParser.AgentIntent intent = intentJsonParser.parse(decisionRaw);
        Map<String, Object> out = new HashMap<>();
        out.put(ChatAgentState.PARSED, intent.isParsed());
        out.put(ChatAgentState.TOOL, intent.getTool());
        out.put(ChatAgentState.ARGS, intent.getArgs() != null ? intent.getArgs() : Map.of());
        out.put(ChatAgentState.REPLY, intent.getReply());

        if (intent.getTool() == null || intent.getTool().isBlank()) {
            boolean hasReply = intent.isParsed() && StringUtils.hasText(intent.getReply());
            out.put(ChatAgentState.USED_TOOL, false);
            out.put(ChatAgentState.ROUTE, hasReply ? "reply" : "fallback");
            if (hasReply) {
                out.put(ChatAgentState.ASSISTANT, intent.getReply().trim());
            }
            return out;
        }

        if (toolRegistry.get(intent.getTool()) == null) {
            String msg = "暂不支持该操作（" + intent.getTool() + "）。你可以试试：查任务、生成图片/视频、提取视频链接、搜知识库。";
            out.put(ChatAgentState.USED_TOOL, true);
            out.put(ChatAgentState.ROUTE, "unknown");
            out.put(ChatAgentState.ASSISTANT, msg);
            return out;
        }

        var tool = toolRegistry.get(intent.getTool());
        boolean write = tool.risk() != null && "WRITE".equals(tool.risk().name());
        out.put(ChatAgentState.USED_TOOL, true);
        out.put(ChatAgentState.ROUTE, write ? "write" : "read");
        return out;
    }

    private String buildDecisionSystemPrompt(List<HybridHit> retrieved) {
        return """
                你是 AI 工具台的意图分类器。根据用户消息决定是否调用工具。
                可用工具：
                %s
                输出必须是单个 JSON 对象，不要其它文字：
                - 需要工具：{"tool":"工具名","args":{...}}
                - 不需要工具：{"tool":null,"reply":"完整中文回复"}
                规则：
                1. 查询任务/模型用 list_my_tasks、get_task、list_chat_models。
                2. 用户要「画图/出图/生成图片」→ draft_imggen（prompt 必填）。
                3. 用户要「生成视频/做短片」→ draft_aigen（prompt 必填）。
                4. 用户给了视频链接要「提取/转录/总结」→ draft_video_extract（url 必填）。
                5. 用户要「搜笔记/知识库里有没有/查我记过的/搜文档」→ search_notes（keyword 必填）。
                6. 用户要「记到知识库/存成笔记/写笔记」→ draft_create_note（content 必填，title 可选）。
                7. draft_* 只会生成待确认草案，不会立刻执行；仍应选择对应 draft 工具。
                8. 禁止编造任务/笔记数据；不确定就 tool=null 并提问。
                9. 下方「知识库检索片段」是当前用户私有笔记，可以直接用来回答；引用时写笔记标题。没有的内容不要编。
                10. 用户明确要求搜索知识库时，即使已有检索片段，也要调用 search_notes 以便前端展示列表卡。
                """.formatted(toolRegistry.describeForPrompt()).stripIndent().trim();
    }

    private String buildDecisionUserPrompt(String userMessage, String historyDigest, List<HybridHit> retrieved) {
        StringBuilder sb = new StringBuilder();
        if (historyDigest != null && !historyDigest.isBlank()) {
            sb.append("最近对话摘要：\n").append(historyDigest).append("\n\n");
        }
        if (retrieved != null && !retrieved.isEmpty()) {
            sb.append("知识库检索片段：\n");
            int i = 1;
            for (HybridHit h : retrieved) {
                sb.append(i++).append(". [").append(h.getSourceType() == null ? "note" : h.getSourceType())
                        .append("] ").append(h.getTitle() == null ? "" : h.getTitle());
                if (h.getFileName() != null && !h.getFileName().isBlank()) {
                    sb.append(" / ").append(h.getFileName());
                }
                sb.append("\n");
                if (h.getSnippet() != null) {
                    String sn = h.getSnippet();
                    if (sn.length() > 400) {
                        sn = sn.substring(0, 400) + "…";
                    }
                    sb.append(sn).append("\n");
                }
            }
            sb.append("\n");
        }
        sb.append("用户本轮消息：\n").append(userMessage != null ? userMessage : "");
        return sb.toString();
    }
}
