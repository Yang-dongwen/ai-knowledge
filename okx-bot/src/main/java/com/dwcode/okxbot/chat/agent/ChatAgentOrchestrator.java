package com.dwcode.okxbot.chat.agent;

import com.dwcode.okxbot.chat.config.AgentProperties;
import com.dwcode.okxbot.chat.stream.StreamCancelledException;
import com.dwcode.okxbot.common.ai.LlmCallOptions;
import com.dwcode.okxbot.common.ai.LlmChatGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Agent 两段式编排：
 * <ol>
 *   <li>决策：短 JSON 意图（tool / reply）</li>
 *   <li>READ 自动执行并总结；WRITE 草案返回确认载荷（不创建任务）</li>
 * </ol>
 * 支持 cancel 检查（与 stop 联动）与 phase 状态回调（前端 loading）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAgentOrchestrator {

    private final AgentProperties agentProperties;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionService toolExecutionService;
    private final IntentJsonParser intentJsonParser;
    private final LlmChatGateway llmChatGateway;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value
    public static class AgentTurnResult {
        /** 最终写入会话的 assistant 文本 */
        String assistantText;
        String toolName;
        ToolResult toolResult;
        boolean usedTool;
        /** 写工具草案：需前端确认 */
        boolean needsConfirm;
    }

    /**
     * @param cancelCheck 返回 true 表示用户已 stop，应中断
     * @param phaseCallback 阶段变更：deciding / tool_running / summarizing（可为 null）
     */
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

        long turnStart = System.currentTimeMillis();
        throwIfCancelled(cancelCheck, toolContext, "before_decision");
        emitPhase(phaseCallback, "deciding");

        String decisionSystem = buildDecisionSystemPrompt();
        String decisionUser = buildDecisionUserPrompt(userMessage, historyDigest);

        LlmCallOptions decisionOpts = LlmCallOptions.builder()
                .temperature(agentProperties.getDecisionTemperature())
                .maxTokens(agentProperties.getDecisionMaxTokens())
                .maxRetries(0)
                .timeoutSeconds(30)
                .build();

        String decisionRaw;
        try {
            decisionRaw = llmChatGateway.chatMessages(
                    java.util.List.of(
                            dev.langchain4j.data.message.SystemMessage.from(decisionSystem),
                            dev.langchain4j.data.message.UserMessage.from(decisionUser)
                    ),
                    providerKey,
                    modelId,
                    decisionOpts
            );
        } catch (Exception e) {
            if (isCancelled(cancelCheck)) {
                AgentAudit.cancelled(
                        toolContext != null ? toolContext.getUserId() : null,
                        toolContext != null ? toolContext.getConversationId() : null,
                        toolContext != null ? toolContext.getStreamId() : null,
                        "decision_llm");
                throw new StreamCancelledException("");
            }
            log.warn("Agent 决策失败，回退纯聊天: {}", e.getMessage());
            return new AgentTurnResult(null, null, null, false, false);
        }

        throwIfCancelled(cancelCheck, toolContext, "after_decision");

        IntentJsonParser.AgentIntent intent = intentJsonParser.parse(decisionRaw);
        log.info("Agent 意图: parsed={}, tool={}, replyLen={}",
                intent.isParsed(), intent.getTool(),
                intent.getReply() != null ? intent.getReply().length() : 0);

        if (intent.getTool() == null || intent.getTool().isBlank()) {
            boolean hasReply = intent.isParsed() && intent.getReply() != null && !intent.getReply().isBlank();
            AgentAudit.decision(
                    toolContext != null ? toolContext.getUserId() : null,
                    toolContext != null ? toolContext.getConversationId() : null,
                    toolContext != null ? toolContext.getStreamId() : null,
                    intent.isParsed(), null, false, false,
                    System.currentTimeMillis() - turnStart);
            if (hasReply) {
                return new AgentTurnResult(intent.getReply().trim(), null, null, false, false);
            }
            // 解析失败且无 reply → 外层回退纯聊天
            return new AgentTurnResult(null, null, null, false, false);
        }

        // 白名单校验（未知工具友好文案，不执行）
        if (toolRegistry.get(intent.getTool()) == null) {
            AgentAudit.toolDenied(intent.getTool(),
                    toolContext != null ? toolContext.getUserId() : null, "NOT_IN_WHITELIST");
            String msg = "暂不支持该操作（" + intent.getTool() + "）。你可以试试：查任务、生成图片/视频、提取视频链接。";
            AgentAudit.decision(
                    toolContext != null ? toolContext.getUserId() : null,
                    toolContext != null ? toolContext.getConversationId() : null,
                    toolContext != null ? toolContext.getStreamId() : null,
                    true, intent.getTool(), false, false,
                    System.currentTimeMillis() - turnStart);
            return new AgentTurnResult(msg, intent.getTool(),
                    ToolResult.fail("UNKNOWN_TOOL", msg), true, false);
        }

        // 单轮工具次数限制（当前固定 1 次；配置 maxToolRounds 预留多轮）
        int maxRounds = Math.max(1, agentProperties.getMaxToolRounds());
        if (maxRounds < 1) {
            maxRounds = 1;
        }
        // 本实现每 turn 只调 1 次工具；若未来多轮循环，在此检查 rounds > maxRounds

        throwIfCancelled(cancelCheck, toolContext, "before_tool");
        emitPhase(phaseCallback, "tool_running");

        ToolResult toolResult = toolExecutionService.execute(
                intent.getTool(), toolContext, intent.getArgs());

        throwIfCancelled(cancelCheck, toolContext, "after_tool");

        boolean needsConfirm = toolResult != null
                && "PENDING_CONFIRM".equals(toolResult.getCode())
                && toolResult.getUi() != null
                && "confirm".equals(String.valueOf(toolResult.getUi().get("type")));

        AgentAudit.decision(
                toolContext != null ? toolContext.getUserId() : null,
                toolContext != null ? toolContext.getConversationId() : null,
                toolContext != null ? toolContext.getStreamId() : null,
                intent.isParsed(), intent.getTool(), true, needsConfirm,
                System.currentTimeMillis() - turnStart);

        if (needsConfirm) {
            String text = toolResult.getMessage() != null
                    ? toolResult.getMessage()
                    : "已准备操作，请确认后执行。";
            return new AgentTurnResult(text, intent.getTool(), toolResult, true, true);
        }

        // 工具失败：可跳过二次 LLM，直接返回可读错误
        if (toolResult != null && !toolResult.isOk()) {
            String failMsg = toolResult.getMessage() != null
                    ? toolResult.getMessage()
                    : "工具执行失败，请稍后重试。";
            return new AgentTurnResult(failMsg, intent.getTool(), toolResult, true, false);
        }

        throwIfCancelled(cancelCheck, toolContext, "before_summary");
        emitPhase(phaseCallback, "summarizing");

        String summary = summarizeToolResult(
                userMessage, intent.getTool(), toolResult, providerKey, modelId, cancelCheck, toolContext);
        throwIfCancelled(cancelCheck, toolContext, "after_summary");
        return new AgentTurnResult(summary, intent.getTool(), toolResult, true, false);
    }

    /** 兼容旧调用（无 cancel / phase） */
    public AgentTurnResult runTurn(String userMessage,
                                   String historyDigest,
                                   String providerKey,
                                   String modelId,
                                   ToolContext toolContext) {
        return runTurn(userMessage, historyDigest, providerKey, modelId, toolContext, () -> false, null);
    }

    private String summarizeToolResult(String userMessage,
                                       String toolName,
                                       ToolResult toolResult,
                                       String providerKey,
                                       String modelId,
                                       Supplier<Boolean> cancelCheck,
                                       ToolContext toolContext) {
        String dataJson;
        try {
            dataJson = objectMapper.writeValueAsString(
                    Map.of(
                            "tool", toolName,
                            "ok", toolResult != null && toolResult.isOk(),
                            "message", toolResult != null ? nullToEmpty(toolResult.getMessage()) : "",
                            "data", toolResult != null && toolResult.getData() != null
                                    ? toolResult.getData() : Map.of()
                    ));
            if (dataJson.length() > 6000) {
                dataJson = dataJson.substring(0, 6000) + "…(truncated)";
            }
        } catch (Exception e) {
            dataJson = toolResult != null ? nullToEmpty(toolResult.getMessage()) : "";
        }

        String system = """
                你是 AI 工具台助手。根据工具返回的真实数据回答用户，用简洁中文。
                禁止编造任务 ID、状态或结果。若工具失败，如实说明原因并给出下一步建议。
                """.stripIndent().trim();
        String user = "用户问题：\n" + userMessage + "\n\n工具 " + toolName + " 返回：\n" + dataJson;

        LlmCallOptions opts = LlmCallOptions.builder()
                .temperature(0.4)
                .maxTokens(agentProperties.getSummaryMaxTokens())
                .maxRetries(0)
                .timeoutSeconds(60)
                .build();
        try {
            throwIfCancelled(cancelCheck, toolContext, "summary_llm");
            return llmChatGateway.chatMessages(
                    java.util.List.of(
                            dev.langchain4j.data.message.SystemMessage.from(system),
                            dev.langchain4j.data.message.UserMessage.from(user)
                    ),
                    providerKey,
                    modelId,
                    opts
            );
        } catch (StreamCancelledException e) {
            throw e;
        } catch (Exception e) {
            if (isCancelled(cancelCheck)) {
                throw new StreamCancelledException("");
            }
            log.warn("工具结果总结失败，使用原始 message: {}", e.getMessage());
            if (toolResult != null && toolResult.getMessage() != null) {
                return toolResult.getMessage();
            }
            return "工具已执行，但生成总结失败。你可在任务列表中查看详情。";
        }
    }

    private String buildDecisionSystemPrompt() {
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
                5. draft_* 只会生成待确认草案，不会立刻执行；仍应选择对应 draft 工具。
                6. 禁止编造任务数据；不确定就 tool=null 并提问。
                """.formatted(toolRegistry.describeForPrompt()).stripIndent().trim();
    }

    private String buildDecisionUserPrompt(String userMessage, String historyDigest) {
        StringBuilder sb = new StringBuilder();
        if (historyDigest != null && !historyDigest.isBlank()) {
            sb.append("最近对话摘要：\n").append(historyDigest).append("\n\n");
        }
        sb.append("用户本轮消息：\n").append(userMessage != null ? userMessage : "");
        return sb.toString();
    }

    private static void emitPhase(Consumer<String> phaseCallback, String phase) {
        if (phaseCallback != null && phase != null) {
            try {
                phaseCallback.accept(phase);
            } catch (Exception ignored) {
                // 状态推送失败不影响主流程
            }
        }
    }

    private static boolean isCancelled(Supplier<Boolean> cancelCheck) {
        try {
            return cancelCheck != null && Boolean.TRUE.equals(cancelCheck.get());
        } catch (Exception e) {
            return false;
        }
    }

    private static void throwIfCancelled(Supplier<Boolean> cancelCheck,
                                         ToolContext toolContext,
                                         String phase) {
        if (isCancelled(cancelCheck)) {
            AgentAudit.cancelled(
                    toolContext != null ? toolContext.getUserId() : null,
                    toolContext != null ? toolContext.getConversationId() : null,
                    toolContext != null ? toolContext.getStreamId() : null,
                    phase);
            throw new StreamCancelledException("");
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
