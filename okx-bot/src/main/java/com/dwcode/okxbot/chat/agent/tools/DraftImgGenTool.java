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
 * 文生图创建草案（不落库，需用户确认后创建）。
 */
@Component
@RequiredArgsConstructor
public class DraftImgGenTool implements AgentTool {

    private final ConfirmTokenService confirmTokenService;

    @Override
    public String name() {
        return "draft_imggen";
    }

    @Override
    public String description() {
        return "准备创建文生图任务（不会立即执行，需用户确认）。"
                + "参数 args.prompt 必填（画面描述）；"
                + "args.aspectRatio 可选 1:1|16:9|9:16，默认 1:1；"
                + "args.n 可选 张数 1-4，默认 1。";
    }

    @Override
    public ToolRisk risk() {
        return ToolRisk.WRITE;
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String prompt = str(args, "prompt", "");
        if (prompt.isBlank()) {
            return ToolResult.fail("BAD_ARGS", "缺少 args.prompt（画面描述）");
        }
        prompt = prompt.trim();
        if (prompt.length() > 2000) {
            prompt = prompt.substring(0, 2000);
        }
        String aspect = str(args, "aspectRatio", "1:1");
        if (!aspect.matches("1:1|16:9|9:16")) {
            aspect = "1:1";
        }
        int n = intArg(args, "n", 1);
        n = Math.max(1, Math.min(4, n));

        Map<String, Object> cleanArgs = new HashMap<>();
        cleanArgs.put("prompt", prompt);
        cleanArgs.put("aspectRatio", aspect);
        cleanArgs.put("n", n);

        String summary = "创建文生图：\"" + truncate(prompt, 60) + "\"，画幅 " + aspect + "，共 " + n + " 张";
        ConfirmTokenService.PendingConfirm pending = confirmTokenService.create(
                ctx.getUserId(), ctx.getConversationId(), name(), cleanArgs, summary);

        return buildConfirmResult(pending, summary, cleanArgs);
    }

    static ToolResult buildConfirmResult(ConfirmTokenService.PendingConfirm pending,
                                         String summary,
                                         Map<String, Object> args) {
        Map<String, Object> data = new HashMap<>();
        data.put("confirmId", pending.getConfirmId());
        data.put("tool", pending.getToolName());
        data.put("summary", summary);
        data.put("args", args);
        data.put("expireAtMs", pending.getExpireAtMs());

        Map<String, Object> ui = new HashMap<>();
        ui.put("type", "confirm");
        ui.put("payload", data);

        return ToolResult.builder()
                .ok(true)
                .code("PENDING_CONFIRM")
                .message("已准备操作，请确认后执行：\n" + summary)
                .data(data)
                .ui(ui)
                .build();
    }

    private static String str(Map<String, Object> args, String key, String def) {
        if (args == null || args.get(key) == null) return def;
        return String.valueOf(args.get(key));
    }

    private static int intArg(Map<String, Object> args, String key, int def) {
        if (args == null || args.get(key) == null) return def;
        Object v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
