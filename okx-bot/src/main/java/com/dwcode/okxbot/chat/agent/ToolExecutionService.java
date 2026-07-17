package com.dwcode.okxbot.chat.agent;

import com.dwcode.okxbot.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 工具执行入口：白名单校验、风险门禁、统一异常与审计。
 * <p>
 * WRITE 工具（draft_*）允许执行：仅生成确认草案，不直接改业务数据。
 * 真正创建由 {@link AgentConfirmService} 在用户确认后完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolExecutionService {

    private final ToolRegistry toolRegistry;

    public ToolResult execute(String toolName, ToolContext ctx, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        AgentTool tool = toolRegistry.get(toolName);
        if (tool == null) {
            AgentAudit.toolDenied(toolName, ctx != null ? ctx.getUserId() : null, "UNKNOWN_TOOL");
            return ToolResult.fail("UNKNOWN_TOOL", "暂不支持该操作（未知工具：" + toolName + "）");
        }
        if (ctx == null || ctx.getUserId() == null) {
            AgentAudit.toolDenied(toolName, null, "NO_USER");
            return ToolResult.fail("NO_USER", "未登录，无法执行工具");
        }

        String risk = tool.risk() != null ? tool.risk().name() : "-";
        AgentAudit.toolStart(toolName, risk, ctx.getUserId(), ctx.getConversationId(), ctx.getStreamId());

        Map<String, Object> safeArgs = args != null ? args : Map.of();
        try {
            ToolResult result = tool.execute(ctx, safeArgs);
            long cost = System.currentTimeMillis() - start;
            boolean ok = result != null && result.isOk();
            String code = result != null ? result.getCode() : "EMPTY";
            AgentAudit.toolEnd(toolName, risk, ctx.getUserId(), ctx.getConversationId(),
                    ctx.getStreamId(), ok, code, cost);
            if (result == null) {
                return ToolResult.fail("EMPTY", "工具无返回，请稍后重试");
            }
            // 统一失败文案前缀，便于前端展示
            if (!result.isOk() && (result.getMessage() == null || result.getMessage().isBlank())) {
                result.setMessage("工具执行失败，请稍后重试或换一种说法");
            }
            return result;
        } catch (BusinessException e) {
            long cost = System.currentTimeMillis() - start;
            AgentAudit.toolEnd(toolName, risk, ctx.getUserId(), ctx.getConversationId(),
                    ctx.getStreamId(), false, "BIZ", cost);
            log.warn("Agent Tool 业务失败: tool={}, err={}", toolName, e.getMessage());
            return ToolResult.fail("BIZ", e.getMessage() != null ? e.getMessage() : "业务校验失败");
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            AgentAudit.toolEnd(toolName, risk, ctx.getUserId(), ctx.getConversationId(),
                    ctx.getStreamId(), false, "ERROR", cost);
            log.error("Agent Tool 异常: tool=" + toolName, e);
            return ToolResult.fail("ERROR", "工具执行异常，请稍后重试");
        }
    }
}
