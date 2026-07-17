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
 * AI 视频生成创建草案。
 */
@Component
@RequiredArgsConstructor
public class DraftAigenTool implements AgentTool {

    private final ConfirmTokenService confirmTokenService;

    @Override
    public String name() {
        return "draft_aigen";
    }

    @Override
    public String description() {
        return "准备创建 AI 视频生成任务（不会立即执行，需用户确认）。"
                + "参数 args.prompt 必填（视频主题/脚本方向）；"
                + "args.aspectRatio 可选 9:16|16:9|1:1，默认 9:16；"
                + "args.targetDurationSec 可选 目标秒数 5-60，默认 15。";
    }

    @Override
    public ToolRisk risk() {
        return ToolRisk.WRITE;
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String prompt = str(args, "prompt", "").trim();
        if (prompt.isBlank()) {
            return ToolResult.fail("BAD_ARGS", "缺少 args.prompt（视频主题描述）");
        }
        if (prompt.length() > 4000) {
            prompt = prompt.substring(0, 4000);
        }
        String aspect = str(args, "aspectRatio", "9:16");
        if (!aspect.matches("9:16|16:9|1:1")) {
            aspect = "9:16";
        }
        int duration = intArg(args, "targetDurationSec", 15);
        duration = Math.max(5, Math.min(60, duration));

        Map<String, Object> cleanArgs = new HashMap<>();
        cleanArgs.put("prompt", prompt);
        cleanArgs.put("aspectRatio", aspect);
        cleanArgs.put("targetDurationSec", duration);

        String summary = "创建 AI 视频：\"" + truncate(prompt, 50) + "\"，" + aspect + "，约 " + duration + " 秒";
        ConfirmTokenService.PendingConfirm pending = confirmTokenService.create(
                ctx.getUserId(), ctx.getConversationId(), name(), cleanArgs, summary);

        return DraftImgGenTool.buildConfirmResult(pending, summary, cleanArgs);
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
