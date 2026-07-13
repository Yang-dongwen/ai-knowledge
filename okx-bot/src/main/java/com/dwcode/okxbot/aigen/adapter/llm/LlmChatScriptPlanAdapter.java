package com.dwcode.okxbot.aigen.adapter.llm;

import com.dwcode.okxbot.aigen.domain.StoryboardDto;
import com.dwcode.okxbot.aigen.port.PlanCommand;
import com.dwcode.okxbot.aigen.port.ScriptPlanPort;
import com.dwcode.okxbot.aigen.service.StoryboardNormalizeService;
import com.dwcode.okxbot.aigen.service.StoryboardValidateService;
import com.dwcode.okxbot.aigen.service.TemplateRegistry;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.client.LlmChatClient;
import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于现有 {@link LlmChatClient} 的规划适配器（OpenAI 兼容协议）。
 * <p>
 * 采用 Port 防腐：业务侧不依赖外部 AI 框架类型。后续可换成
 * LangChain4j AiServices 实现同一 {@link ScriptPlanPort}。
 * Phase 1：强 prompt + JSON 解析 + repair。
 */
@Slf4j
@RequiredArgsConstructor
public class LlmChatScriptPlanAdapter implements ScriptPlanPort {

    private static final Pattern JSON_BLOCK = Pattern.compile("(?s)```(?:json)?\\s*(\\{.*?})\\s*```");

    private final LlmChatClient llmChatClient;
    private final ObjectMapper objectMapper;
    private final StoryboardValidateService validateService;
    private final StoryboardNormalizeService normalizeService;
    private final TemplateRegistry templateRegistry;
    private final AigenProperties aigenProperties;

    @Override
    public StoryboardDto plan(PlanCommand command) {
        var def = templateRegistry.require(command.getTemplateId());
        String system = buildSystemPrompt(command, def.allowedSceneTypes().toString());
        String user = "用户主题/提示词：\n" + command.getPrompt();

        String provider = command.getLlmProvider();
        String model = command.getLlmModel();

        try {
            String raw = llmChatClient.chat(system, user, provider, model);
            StoryboardDto dto = parseStoryboard(raw);
            dto = normalizeService.normalize(
                    dto,
                    command.getTemplateId(),
                    command.getAspectRatio(),
                    command.getTargetDurationSec(),
                    command.getLanguage(),
                    command.getTitleHint()
            );
            List<String> errors = validateService.validate(
                    dto, command.getTemplateId(), command.getAspectRatio(), command.getTargetDurationSec());
            if (!errors.isEmpty()) {
                int maxRepair = Math.max(0, aigenProperties.getLlm().getMaxRepairAttempts());
                if (maxRepair > 0) {
                    log.warn("分镜校验失败，尝试 repair: {}", errors);
                    String repairUser = "以下 JSON 不合法，错误: " + errors
                            + "\n请输出修复后的完整 JSON：\n" + objectMapper.writeValueAsString(dto);
                    String repaired = llmChatClient.chat(
                            "你是 JSON 修复器，只输出合法分镜 JSON，不要 markdown。",
                            repairUser, provider, model);
                    dto = parseStoryboard(repaired);
                    dto = normalizeService.normalize(
                            dto,
                            command.getTemplateId(),
                            command.getAspectRatio(),
                            command.getTargetDurationSec(),
                            command.getLanguage(),
                            command.getTitleHint()
                    );
                    errors = validateService.validate(
                            dto, command.getTemplateId(), command.getAspectRatio(), command.getTargetDurationSec());
                }
            }
            if (!errors.isEmpty()) {
                throw new BusinessException("分镜校验失败: " + String.join("; ", errors));
            }
            return dto;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("LLM 规划失败: {}", e.getMessage());
            throw new BusinessException("规划失败: " + e.getMessage());
        }
    }

    private StoryboardDto parseStoryboard(String raw) throws Exception {
        String json = extractJson(raw);
        return objectMapper.readValue(json, StoryboardDto.class);
    }

    static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("LLM 返回为空");
        }
        String t = raw.trim();
        Matcher m = JSON_BLOCK.matcher(t);
        if (m.find()) {
            return m.group(1);
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return t.substring(start, end + 1);
        }
        throw new BusinessException("无法从 LLM 响应解析 JSON");
    }

    private String buildSystemPrompt(PlanCommand cmd, String allowedTypes) {
        int fps = 30;
        int[] wh = "16:9".equals(cmd.getAspectRatio())
                ? new int[]{1920, 1080}
                : ("1:1".equals(cmd.getAspectRatio()) ? new int[]{1080, 1080} : new int[]{1080, 1920});
        return """
                你是短视频分镜编剧。只输出一个 JSON 对象，不要 markdown，不要解释。
                约束：
                1. version 固定 "1.0"
                2. meta.templateId 固定为 %s
                3. meta.language=%s, meta.fps=%d, meta.width=%d, meta.height=%d
                4. scenes 2～8 个；每场 type 只能是: %s
                5. 每场必须有 id、type、narration（口播，中文口语）、props
                6. title 场 props 含 title/subtitle；bullets 场 props 含 heading 与 items；outro 场 props 含 title/cta
                7. items 必须是字符串数组，例如 ["要点一","要点二"]，禁止 [{text:"..."}] 对象数组
                8. 总时长约 %d 秒；可给 durationInFrames，系统会再规范化
                9. 不要输出任何 http(s) URL 或文件路径
                10. audio 与 subtitles 可省略
                JSON 结构示例字段: version, meta{title,language,templateId,fps,width,height,durationInFrames}, style{theme,primaryColor}, scenes[{id,type,startFrame,durationInFrames,narration,props{title,subtitle,heading,items,cta}}]
                """.formatted(
                cmd.getTemplateId(),
                cmd.getLanguage() != null ? cmd.getLanguage() : "zh",
                fps, wh[0], wh[1],
                allowedTypes,
                cmd.getTargetDurationSec()
        );
    }
}
