package com.dwcode.okxbot.aigen.adapter.llm;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.domain.shot.ShotlistDto;
import com.dwcode.okxbot.aigen.port.DirectorCommand;
import com.dwcode.okxbot.aigen.port.DirectorPort;
import com.dwcode.okxbot.aigen.service.ShotlistNormalizeService;
import com.dwcode.okxbot.aigen.service.ShotlistValidateService;
import com.dwcode.okxbot.common.ai.ChatModelFactory;
import com.dwcode.okxbot.common.ai.LlmCallOptions;
import com.dwcode.okxbot.common.ai.LlmChatClient;
import com.dwcode.okxbot.common.ai.LlmContentHelper;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Visual Timeline 导演：LangChain4j AiServices + JSON 模式 → Shotlist。
 */
@Slf4j
@RequiredArgsConstructor
public class LangChain4jDirectorAdapter implements DirectorPort {

    private final ChatModelFactory chatModelFactory;
    private final LlmChatClient llmChatClient;
    private final ObjectMapper objectMapper;
    private final ShotlistValidateService validateService;
    private final ShotlistNormalizeService normalizeService;
    private final AigenProperties aigenProperties;

    interface ShotlistJsonPlanner {
        @SystemMessage("{{systemPrompt}}")
        @UserMessage("{{userPrompt}}")
        String planJson(@V("systemPrompt") String systemPrompt, @V("userPrompt") String userPrompt);
    }

    @Override
    public ShotlistDto plan(DirectorCommand command) {
        String system = buildSystem(command);
        String user = "用户主题/提示词：\n" + command.getPrompt();
        LlmCallOptions options = buildOptions();

        try {
            String raw = invoke(system, user, command.getLlmProvider(), command.getLlmModel(), options);
            ShotlistDto dto = parse(raw);
            dto = normalizeService.normalize(
                    dto,
                    command.getAspectRatio(),
                    command.getTargetDurationSec(),
                    command.getLanguage(),
                    command.getStylePreset(),
                    command.getAudioMode(),
                    command.getTitleHint()
            );
            List<String> errors = validateService.validate(dto, command.getTargetDurationSec());
            if (!errors.isEmpty()) {
                int maxRepair = Math.max(0, aigenProperties.getLlm().getMaxRepairAttempts());
                if (maxRepair > 0) {
                    log.warn("镜头表校验失败，repair: {}", errors);
                    String repairUser = "以下 JSON 不合法，错误: " + errors
                            + "\n请输出修复后的完整镜头表 JSON：\n"
                            + objectMapper.writeValueAsString(dto);
                    String repaired = invoke(
                            "你是 JSON 修复器，只输出合法 vt-1.0 镜头表 JSON，不要 markdown。",
                            repairUser, command.getLlmProvider(), command.getLlmModel(), options);
                    dto = parse(repaired);
                    dto = normalizeService.normalize(
                            dto,
                            command.getAspectRatio(),
                            command.getTargetDurationSec(),
                            command.getLanguage(),
                            command.getStylePreset(),
                            command.getAudioMode(),
                            command.getTitleHint()
                    );
                    errors = validateService.validate(dto, command.getTargetDurationSec());
                }
            }
            if (!errors.isEmpty()) {
                throw new BusinessException("镜头表校验失败: " + String.join("; ", errors));
            }
            return dto;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("导演规划失败: {}", e.getMessage());
            throw new BusinessException("镜头规划失败: " + e.getMessage());
        }
    }

    private String invoke(String system, String user, String provider, String model, LlmCallOptions options) {
        try {
            ChatModel chatModel = chatModelFactory.create(provider, model, options);
            ShotlistJsonPlanner planner = AiServices.builder(ShotlistJsonPlanner.class)
                    .chatModel(chatModel)
                    .build();
            String raw = planner.planJson(system, user);
            if (raw == null || raw.isBlank()) {
                throw new BusinessException("AiServices 返回空镜头表");
            }
            return raw;
        } catch (Exception e) {
            log.warn("AiServices 导演失败，回退 LlmChatClient: {}", e.getMessage());
            return llmChatClient.chat(system, user, provider, model, options);
        }
    }

    private ShotlistDto parse(String raw) throws Exception {
        String json = LlmContentHelper.extractJsonObject(raw);
        return objectMapper.readValue(json, ShotlistDto.class);
    }

    private LlmCallOptions buildOptions() {
        AigenProperties.Llm llm = aigenProperties.getLlm();
        LlmCallOptions.LlmCallOptionsBuilder b = LlmCallOptions.builder()
                .temperature(llm.getTemperature())
                .maxTokens(llm.getMaxTokens())
                .maxRetries(llm.getMaxRetries())
                .timeoutSeconds(Math.max(30, llm.getTimeoutSeconds()));
        if (LangChain4jScriptPlanAdapter.wantJsonObjectMode(llm.getStructuredMode())) {
            b.responseFormat("json_object");
        }
        return b.build();
    }

    private static String narrationRule(String audioMode) {
        String m = audioMode != null ? audioMode.trim().toLowerCase() : "";
        if ("tts".equals(m) || "tts_bgm".equals(m)) {
            return "当前需要口播：每镜必须填 narration（与用户提示词同语言，口语化完整句子，禁止空字符串）；"
                    + "可与 overlay 标题呼应，但 narration 不能省略";
        }
        return "当前无强制口播：narration 可省略；若填写须与用户提示词同语言";
    }

    /**
     * 语言策略：以用户提示词语言为准，禁止强制英文化。
     */
    private static String languagePolicy(String language) {
        String lang = language != null ? language.trim().toLowerCase(java.util.Locale.ROOT) : "zh";
        if (lang.startsWith("en")) {
            return "语言：用户提示词为英文语境时，narration / overlay / visual.prompt 全部使用英文，勿擅自译成中文。";
        }
        return "语言：必须与用户提示词使用相同语言。"
                + "用户写中文则 narration、overlay 文案、visual.prompt 全部用中文；"
                + "用户写英文则全部用英文。"
                + "禁止把画面 prompt 强制翻译成英文，也禁止擅自改写用户语言。";
    }

    private String buildSystem(DirectorCommand cmd) {
        int minS = aigenProperties.getVisual().getMinShots();
        int maxS = aigenProperties.getVisual().getMaxShots();
        String lang = cmd.getLanguage() != null ? cmd.getLanguage() : "zh";
        return """
                你是短视频视觉导演。只输出一个 JSON 对象（vt-1.0 镜头表），不要 markdown，不要解释。
                字段：
                {
                  "version":"vt-1.0",
                  "meta":{"title":"","language":"%s","aspectRatio":"%s","targetDurationSec":%d,"stylePreset":"%s"},
                  "audio":{"mode":"%s"},
                  "shots":[{
                    "id":"shot-1","durationSec":3.5,
                    "narration":"与用户同语言的口播，口语化，15～40字",
                    "visual":{"type":"ai_image","prompt":"与用户同语言的画面描述，电影感光影构图，画面无文字"},
                    "motion":{"type":"ken_burns"},
                    "transition":{"type":"crossfade","durationFrames":12},
                    "overlay":{"layout":"hook-center","title":"与用户同语言的大标题","subtitle":"","bullets":[]}
                  }]
                }
                硬性规则：
                1. shots 数量 %d～%d，总时长约 %d 秒
                2. visual.type 默认 ai_image；prompt 为画面描述，必须与用户提示词同语言（勿强制英文），禁止 http URL；画面中不要出现可读文字/水印
                3. overlay.layout 只能是: none, hook-center, lower-third, bullets-right, caption
                4. motion.type: static 或 ken_burns 或 pan_left 或 pan_right 或 zoom_in 或 zoom_out
                5. 叠字简短有力、与用户同语言；画面 prompt 丰富具体且同语言
                6. 叙事建议：钩子 → 展开 → 对比/洞察 → 收束
                7. 不要输出 audio 文件路径；audio.mode 保持为 %s
                8. %s
                9. %s
                风格预设 %s：据此调整画面与叠字语气。
                """.formatted(
                lang,
                cmd.getAspectRatio() != null ? cmd.getAspectRatio() : "9:16",
                cmd.getTargetDurationSec(),
                cmd.getStylePreset() != null ? cmd.getStylePreset() : "cinematic-dark",
                cmd.getAudioMode() != null ? cmd.getAudioMode() : "bgm_only",
                minS, maxS,
                cmd.getTargetDurationSec(),
                cmd.getAudioMode() != null ? cmd.getAudioMode() : "bgm_only",
                narrationRule(cmd.getAudioMode()),
                languagePolicy(lang),
                cmd.getStylePreset() != null ? cmd.getStylePreset() : "cinematic-dark"
        );
    }
}
