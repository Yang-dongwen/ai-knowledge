package com.dwcode.okxbot.chat.agent.tools;

import com.dwcode.okxbot.chat.agent.AgentTool;
import com.dwcode.okxbot.chat.agent.ToolContext;
import com.dwcode.okxbot.chat.agent.ToolResult;
import com.dwcode.okxbot.chat.agent.ToolRisk;
import com.dwcode.okxbot.common.ai.AiModelConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 列出可用 Chat 模型（capability=chat 且供应商有 api-key）。
 */
@Component
@RequiredArgsConstructor
public class ListChatModelsTool implements AgentTool {

    private final AiModelConfigService aiModelConfigService;

    @Override
    public String name() {
        return "list_chat_models";
    }

    @Override
    public String description() {
        return "列出当前可用的 Chat 对话模型（按供应商分组）。无需参数。";
    }

    @Override
    public ToolRisk risk() {
        return ToolRisk.READ;
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        List<Map<String, Object>> grouped = aiModelConfigService.listEnabledGroupedByProvider();
        int modelCount = 0;
        if (grouped != null) {
            for (Map<String, Object> g : grouped) {
                Object models = g.get("models");
                if (models instanceof List<?> list) {
                    modelCount += list.size();
                }
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("providers", grouped != null ? grouped : List.of());
        data.put("modelCount", modelCount);

        Map<String, Object> ui = new HashMap<>();
        ui.put("type", "model_list");
        ui.put("payload", data);

        String msg = modelCount == 0
                ? "暂无可用 Chat 模型，请在模型管理中配置。"
                : "当前共 " + modelCount + " 个可用 Chat 模型。";
        return ToolResult.success(msg, data, ui);
    }
}
