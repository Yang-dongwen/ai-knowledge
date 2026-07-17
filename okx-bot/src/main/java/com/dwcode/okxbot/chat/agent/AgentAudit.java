package com.dwcode.okxbot.chat.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 工具调用审计：统一结构化日志，便于检索与排障。
 * <p>
 * 格式：{@code AGENT_AUDIT event=... key=value ...}
 */
public final class AgentAudit {

    private static final Logger log = LoggerFactory.getLogger("AGENT_AUDIT");

    private AgentAudit() {
    }

    public static void decision(Long userId, Long conversationId, String streamId,
                                boolean parsed, String tool, boolean usedTool, boolean needsConfirm,
                                long costMs) {
        log.info("AGENT_AUDIT event=decision userId={} convId={} streamId={} parsed={} tool={} usedTool={} needsConfirm={} costMs={}",
                userId, conversationId, streamId, parsed, nullToDash(tool), usedTool, needsConfirm, costMs);
    }

    public static void toolStart(String tool, String risk, Long userId, Long conversationId, String streamId) {
        log.info("AGENT_AUDIT event=tool_start tool={} risk={} userId={} convId={} streamId={}",
                nullToDash(tool), nullToDash(risk), userId, conversationId, streamId);
    }

    public static void toolEnd(String tool, String risk, Long userId, Long conversationId, String streamId,
                               boolean ok, String code, long costMs) {
        log.info("AGENT_AUDIT event=tool_end tool={} risk={} userId={} convId={} streamId={} ok={} code={} costMs={}",
                nullToDash(tool), nullToDash(risk), userId, conversationId, streamId,
                ok, nullToDash(code), costMs);
    }

    public static void toolDenied(String tool, Long userId, String reason) {
        log.warn("AGENT_AUDIT event=tool_denied tool={} userId={} reason={}",
                nullToDash(tool), userId, nullToDash(reason));
    }

    public static void confirm(String action, String confirmId, Long userId, Long conversationId,
                               String tool, boolean ok, long costMs) {
        log.info("AGENT_AUDIT event=confirm action={} confirmId={} userId={} convId={} tool={} ok={} costMs={}",
                nullToDash(action), nullToDash(confirmId), userId, conversationId,
                nullToDash(tool), ok, costMs);
    }

    public static void cancelled(Long userId, Long conversationId, String streamId, String phase) {
        log.info("AGENT_AUDIT event=cancelled userId={} convId={} streamId={} phase={}",
                userId, conversationId, streamId, nullToDash(phase));
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
