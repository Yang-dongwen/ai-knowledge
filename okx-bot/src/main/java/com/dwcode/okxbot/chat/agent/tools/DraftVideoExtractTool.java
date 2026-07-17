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
import java.util.regex.Pattern;

/**
 * 视频提取创建草案。
 */
@Component
@RequiredArgsConstructor
public class DraftVideoExtractTool implements AgentTool {

    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");

    private final ConfirmTokenService confirmTokenService;

    @Override
    public String name() {
        return "draft_video_extract";
    }

    @Override
    public String description() {
        return "准备创建视频提取任务（转录+总结，不会立即执行，需用户确认）。"
                + "参数 args.url 必填（抖音/B站/YouTube 等公开视频链接）。";
    }

    @Override
    public ToolRisk risk() {
        return ToolRisk.WRITE;
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        String url = str(args, "url", "").trim();
        if (url.isBlank()) {
            // 尝试从 message 类字段兜底
            url = str(args, "link", "").trim();
        }
        if (url.isBlank() || !URL.matcher(url).find()) {
            return ToolResult.fail("BAD_ARGS", "缺少有效的 args.url（需 http/https 视频链接）");
        }
        // 只取第一个 URL
        var m = URL.matcher(url);
        if (m.find()) {
            url = m.group().replaceAll("[)\\],.，。]+$", "");
        }
        if (url.length() > 2000) {
            return ToolResult.fail("BAD_ARGS", "url 过长");
        }

        Map<String, Object> cleanArgs = new HashMap<>();
        cleanArgs.put("url", url);

        String summary = "创建视频提取：\n" + truncate(url, 120);
        ConfirmTokenService.PendingConfirm pending = confirmTokenService.create(
                ctx.getUserId(), ctx.getConversationId(), name(), cleanArgs, summary);

        return DraftImgGenTool.buildConfirmResult(pending, summary, cleanArgs);
    }

    private static String str(Map<String, Object> args, String key, String def) {
        if (args == null || args.get(key) == null) return def;
        return String.valueOf(args.get(key));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
