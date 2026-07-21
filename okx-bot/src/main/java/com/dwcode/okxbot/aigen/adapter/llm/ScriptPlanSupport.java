package com.dwcode.okxbot.aigen.adapter.llm;

import com.dwcode.okxbot.aigen.domain.StoryboardDto;
import com.dwcode.okxbot.aigen.port.PlanCommand;
import com.dwcode.okxbot.aigen.service.TemplateRegistry;
import com.dwcode.okxbot.common.ai.LlmContentHelper;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 分镜规划共用：prompt 拼装 + JSON 解析（两套 Adapter 共用，避免漂移）。
 */
public final class ScriptPlanSupport {

    private ScriptPlanSupport() {
    }

    public static String buildSystemPrompt(PlanCommand cmd, String allowedTypes) {
        int fps = 30;
        int[] wh = "16:9".equals(cmd.getAspectRatio())
                ? new int[]{1920, 1080}
                : ("1:1".equals(cmd.getAspectRatio()) ? new int[]{1080, 1080} : new int[]{1080, 1920});
        String lang = cmd.getLanguage() != null ? cmd.getLanguage() : "zh";
        String common = """
                你是短视频分镜编剧。只输出一个 JSON 对象，不要 markdown，不要解释。
                字符串值内禁止未转义英文双引号 "；引用用「」或写成 \\"词\\"。
                通用约束：
                1. version 固定 "1.0"
                2. meta.templateId 固定为 %s
                3. meta.language=%s, meta.fps=%d, meta.width=%d, meta.height=%d
                4. scenes 4～8 个；每场 type 只能是: %s
                5. 每场必须有 id、type、narration（口播，口语化）、props
                6. props 必须是 JSON 对象（花括号），禁止数组！正确: "props":{"title":"标题","eyebrow":"标签"}；错误: "props":["title=标题"]
                7. items/leftItems/rightItems 必须是字符串数组，例如 ["要点一","要点二"]，禁止对象数组
                8. 画面 props 字段值为短短语，口播 narration 可稍长
                9. 总时长约 %d 秒；可给 durationInFrames，系统会再规范化
                10. 不要输出任何 http(s) URL 或文件路径；audio 与 subtitles 可省略
                11. 语言必须与用户提示词一致：用户写中文则 narration/props 用中文，写英文则用英文；禁止擅自翻译成另一种语言
                """.formatted(
                cmd.getTemplateId(),
                lang,
                fps, wh[0], wh[1],
                allowedTypes,
                cmd.getTargetDurationSec()
        );
        if (TemplateRegistry.INSIGHT_COMPARE.equals(cmd.getTemplateId())) {
            return common + """
                    模板 insight-compare 专用：
                    - 叙事结构必须：hook → 至少 1 个 compare →（insight 或 metric 至少一个）→ outro
                    - 禁止连续 3 个相同 type
                    - hook: props 含 eyebrow、title、subtitle?
                    - compare: props 含 heading?、leftLabel、rightLabel、leftItems(2～4)、rightItems(2～4)；左右短语各≤16字
                    - insight: props 含 heading、items(2～3 条)
                    - metric: props 含 value、unit?、label、hint?
                    - outro: props 含 title、cta
                    - style.theme 可用 "compare-duo"；primaryColor 用醒目色如 #0ea5e9
                    - 正确示例: "props":{"eyebrow":"误区","title":"先场景还是先模型？","subtitle":"路径决定结果"}
                    - compare 正确示例: "props":{"heading":"对比","leftLabel":"无效","rightLabel":"有效","leftItems":["堆概念","无验收"],"rightItems":["锁场景","最小闭环"]}
                    """;
        }
        return common + """
                模板 knowledge 类专用：
                - title 场 props 含 title/subtitle
                - bullets 场 props 含 heading 与 items（建议 3 条）
                - outro 场 props 含 title/cta
                - 避免全片只有 bullets；可 1 个 title + 2～4 bullets + outro
                JSON 字段: version, meta{title,language,templateId,fps,width,height,durationInFrames}, style{theme,primaryColor}, scenes[{id,type,startFrame,durationInFrames,narration,props{title,subtitle,heading,items,cta}}]
                """;
    }

    public static StoryboardDto parseStoryboard(ObjectMapper objectMapper, String raw) {
        try {
            return LlmContentHelper.parseJsonAs(objectMapper, raw, StoryboardDto.class);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("分镜 JSON 解析失败: " + e.getMessage());
        }
    }
}
