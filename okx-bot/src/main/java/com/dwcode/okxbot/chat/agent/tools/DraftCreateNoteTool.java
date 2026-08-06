package com.dwcode.okxbot.chat.agent.tools;

import com.dwcode.okxbot.chat.agent.AgentTool;
import com.dwcode.okxbot.chat.agent.ConfirmTokenService;
import com.dwcode.okxbot.chat.agent.ToolContext;
import com.dwcode.okxbot.chat.agent.ToolResult;
import com.dwcode.okxbot.chat.agent.ToolRisk;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 创建知识库笔记草案（WRITE，需用户确认）。
 */
@Component
@RequiredArgsConstructor
public class DraftCreateNoteTool implements AgentTool {

    private final ConfirmTokenService confirmTokenService;

    @Override
    public String name() {
        return "draft_create_note";
    }

    @Override
    public String description() {
        return "准备创建一条个人知识库笔记（不会立即写入，需用户确认）。"
                + "参数 args.title 可选；args.content 必填（正文，支持 markdown）；"
                + "args.contentFormat 可选 html|markdown，默认 markdown。";
    }

    @Override
    public ToolRisk risk() {
        return ToolRisk.WRITE;
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String title = str(args, "title", "").trim();
        String content = str(args, "content", "").trim();
        if (content.isBlank()) {
            return ToolResult.fail("BAD_ARGS", "缺少 args.content（笔记正文）");
        }
        if (content.length() > 100_000) {
            content = content.substring(0, 100_000);
        }
        if (title.length() > 200) {
            title = title.substring(0, 200);
        }
        String format = str(args, "contentFormat", "markdown").trim().toLowerCase();
        if (!"html".equals(format) && !"markdown".equals(format)) {
            format = "markdown";
        }

        Map<String, Object> cleanArgs = new HashMap<>();
        if (!title.isBlank()) {
            cleanArgs.put("title", title);
        }
        cleanArgs.put("content", content);
        cleanArgs.put("contentFormat", format);

        String preview = title.isBlank()
                ? truncate(content.replace('\n', ' '), 50)
                : title;
        String summary = "创建知识库笔记：「" + preview + "」";
        ConfirmTokenService.PendingConfirm pending = confirmTokenService.create(
                ctx.getUserId(), ctx.getConversationId(), name(), cleanArgs, summary);

        return DraftImgGenTool.buildConfirmResult(pending, summary, cleanArgs);
    }

    private static String str(Map<String, Object> args, String key, String def) {
        if (args == null || args.get(key) == null) {
            return def;
        }
        return String.valueOf(args.get(key));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
