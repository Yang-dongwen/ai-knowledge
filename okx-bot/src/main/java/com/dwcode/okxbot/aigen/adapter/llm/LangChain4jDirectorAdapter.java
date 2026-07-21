package com.dwcode.okxbot.aigen.adapter.llm;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.domain.shot.ShotlistDto;
import com.dwcode.okxbot.aigen.port.DirectorCommand;
import com.dwcode.okxbot.aigen.port.DirectorPort;
import com.dwcode.okxbot.aigen.service.ShotlistNormalizeService;
import com.dwcode.okxbot.aigen.service.ShotlistValidateService;
import com.dwcode.okxbot.aigen.service.TopicRelevanceService;
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
    private final TopicRelevanceService topicRelevanceService;
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
            ShotlistDto dto = parseWithSyntaxRepair(raw, command, options);
            dto = normalizeService.normalize(
                    dto,
                    command.getAspectRatio(),
                    command.getTargetDurationSec(),
                    command.getLanguage(),
                    command.getStylePreset(),
                    command.getAudioMode(),
                    command.getTitleHint()
            );
            List<String> errors = new java.util.ArrayList<>(
                    validateService.validate(dto, command.getTargetDurationSec()));
            if (aigenProperties.getVisual() != null
                    && aigenProperties.getVisual().isEnforceTopicKeywords()) {
                errors.addAll(topicRelevanceService.validateShotlist(dto, command.getPrompt()));
            }
            if (!errors.isEmpty()) {
                int maxRepair = Math.max(0, aigenProperties.getLlm().getMaxRepairAttempts());
                if (maxRepair > 0) {
                    log.warn("镜头表校验失败，repair: {}", errors);
                    String anchors = String.join(", ",
                            topicRelevanceService.extractAnchors(command.getPrompt()));
                    String repairUser = "以下 JSON 业务校验失败，错误: " + errors
                            + "\n用户原主题: " + command.getPrompt()
                            + (anchors.isBlank() ? "" : "\n必须写入各镜的主题关键词: " + anchors)
                            + "\n请输出修复后的完整镜头表 JSON（每镜 visual.prompt 必须与用户同语言且扣题；promptEn 可选）。"
                            + "字符串内禁止未转义双引号，引用请用「」或 \\\" ：\n"
                            + objectMapper.writeValueAsString(dto);
                    String repaired = invoke(
                            "你是镜头表修复器，只输出合法 vt-1.0 JSON，不要 markdown。"
                                    + "修复时确保每镜 visual.prompt 使用用户语言并包含主题主体，"
                                    + "禁止空镜赛博都市代替主题；不要强行改成英文主描述。"
                                    + "JSON 字符串内禁止裸双引号，可用「」或转义 \\\"。",
                            repairUser, command.getLlmProvider(), command.getLlmModel(), options);
                    dto = parseWithSyntaxRepair(repaired, command, options);
                    dto = normalizeService.normalize(
                            dto,
                            command.getAspectRatio(),
                            command.getTargetDurationSec(),
                            command.getLanguage(),
                            command.getStylePreset(),
                            command.getAudioMode(),
                            command.getTitleHint()
                    );
                    errors = new java.util.ArrayList<>(
                            validateService.validate(dto, command.getTargetDurationSec()));
                    if (aigenProperties.getVisual() != null
                            && aigenProperties.getVisual().isEnforceTopicKeywords()) {
                        errors.addAll(topicRelevanceService.validateShotlist(dto, command.getPrompt()));
                    }
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

    /**
     * 解析镜头表；若语法损坏则本地修复失败后再请求 LLM 修 JSON（与业务校验 repair 独立）。
     */
    private ShotlistDto parseWithSyntaxRepair(String raw, DirectorCommand command, LlmCallOptions options) {
        try {
            return parse(raw);
        } catch (BusinessException first) {
            int maxRepair = Math.max(0, aigenProperties.getLlm().getMaxRepairAttempts());
            if (maxRepair <= 0) {
                throw first;
            }
            log.warn("镜头表 JSON 语法损坏，尝试 LLM 修复: {}", first.getMessage());
            String snippet = LlmContentHelper.truncate(raw, 6000);
            String repairUser = "以下文本不是合法 JSON（错误: " + first.getMessage() + "）。\n"
                    + "请输出完整合法的 vt-1.0 镜头表 JSON。"
                    + "字符串值内禁止未转义双引号；引用用「」或 \\\"。不要 markdown。\n"
                    + "用户主题: " + command.getPrompt() + "\n原文:\n" + snippet;
            try {
                String repaired = invoke(
                        "你是 JSON 修复器，只输出合法 vt-1.0 镜头表 JSON 对象，不要解释，不要 markdown。",
                        repairUser,
                        command.getLlmProvider(),
                        command.getLlmModel(),
                        options);
                return parse(repaired);
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                throw new BusinessException("镜头规划失败: " + e.getMessage());
            }
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

    private ShotlistDto parse(String raw) {
        return LlmContentHelper.parseJsonAs(objectMapper, raw, ShotlistDto.class);
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
        String style = cmd.getStylePreset() != null ? cmd.getStylePreset() : "cinematic-dark";
        String audio = cmd.getAudioMode() != null ? cmd.getAudioMode() : "bgm_only";
        String aspect = cmd.getAspectRatio() != null ? cmd.getAspectRatio() : "9:16";
        return """
                你是顶级短片视觉导演 + 剪辑师。任务：把用户主题变成「可观看且主题高度相关」的电影感镜头表，不是填模板，更不是随便拍空镜。
                只输出一个 JSON 对象（vt-1.0），不要 markdown，不要解释。
                JSON 字符串纪律（违反会导致解析失败）：
                - 字符串值内禁止未转义的英文双引号 "；引用请用「」或 『』，或写成 \\"AI\\"
                - 禁止把弯引号混用搞乱结构；优先用中文直角引号包专有名词
                - 不要在 prompt 中写裸的 "AI"，应写「AI」或 AI（无引号）

                【主题忠诚——最高优先级，违反则整表失败】
                - 用户主题里的核心实体/事件/时间线必须在每一镜 visual.prompt 里可识别出现。
                  例：用户写「以太坊加密货币历史」→ 每镜必须出现以太坊/Ethereum/ETH 相关可视主体
                  （白皮书手稿、创世区块、矿机与显卡、智能合约界面、区块链节点网络、合并升级、DeFi/NFT 场景等），
                  禁止整片都是与主题无关的城市夜景、路人、空洞赛博都市。
                - 允许隐喻，但隐喻必须「一眼能联想到主题」：先写具体主体，再写光影风格；禁止只有光影没有主体。
                - 从用户原文提取 3～8 个关键词，分散写入各镜 prompt（专有名词优先保留，勿泛化成「科技」「未来」）。
                - 叙事片：镜头顺序应覆盖用户主题的关键节点（历史进程则按时间/阶段推进），不要跳戏。

                创作原则：
                - 禁止模板腔：不要机械「开场-展开-对比-收束」八股；按主题自由发明叙事节奏。
                - 画面优先：每一镜 visual.prompt 必须是具体可拍摄场景（主体、环境、光影、镜头景别、氛围、材质）。
                - 为「真动态」构图：有纵深与可运动空间；相邻 motion.type 尽量不同。
                - 叠字克制：多数 overlay.layout=none；仅情绪峰值加短标题，标题也须点题。
                - 画面内尽量不要大段可读正文/水印；但主题标志物、图标化符号、界面示意可以描述（勿要求清晰可OCR长文）。
                - 节奏：单镜 durationSec 优先 2.2～4.5。
                - 风格预设「%s」：统一气质，但每镜主体仍须扣题。
                - visual.type 写 ai_image；不要输出假 URL。

                JSON 形态：
                {
                  "version":"vt-1.0",
                  "meta":{"title":"短标题","language":"%s","aspectRatio":"%s","targetDurationSec":%d,"stylePreset":"%s"},
                  "audio":{"mode":"%s"},
                  "shots":[{
                    "id":"shot-1",
                    "durationSec":3.2,
                    "narration":"可选口播",
                    "visual":{
                      "type":"ai_image",
                      "prompt":"具体画面描述（与用户同语言，必须含主题主体；用户中文则中文）",
                      "promptEn":"optional English backup with same subjects (not required for Chinese users)",
                      "negativePrompt":"unrelated cityscape, random people, watermark, blurry, low quality, deformed, wrong subject"
                    },
                    "motion":{
                      "type":"punch_in",
                      "params":{"intensity":0.75,"scaleFrom":1.0,"scaleTo":1.18,"xFrom":0,"xTo":-2,"yFrom":0,"yTo":1,"rotateFrom":0,"rotateTo":0}
                    },
                    "transition":{"type":"crossfade","durationFrames":10},
                    "overlay":{
                      "layout":"none",
                      "title":"",
                      "subtitle":"",
                      "bullets":[],
                      "position":"center",
                      "style":"cinematic",
                      "textAnim":"pop"
                    },
                    "notes":"可选：本镜情绪/剪辑意图"
                  }]
                }

                字段可选值：
                - motion.type：static | ken_burns | zoom_in | zoom_out | pan_left | pan_right
                  | punch_in | punch_out | whip | drift | shake | orbit | tilt | rise | fall | auto
                - motion.params（可部分省略，数值建议）：
                  intensity 0~1；scaleFrom/scaleTo；xFrom/xTo；yFrom/yTo（百分比偏移）；
                  rotateFrom/rotateTo（度）
                - transition.type：crossfade | hard_cut | flash | dip_black | dip_white | wipe_left | wipe_right
                - overlay.layout：none | free | hook-center | lower-third | bullets-right | caption | big-word | corner
                - overlay.style：cinematic | bold-impact | soft | neon | minimal
                - overlay.textAnim：none | fade | pop | slide_up | slide_left | typewriter | glitch
                - overlay.position：center | top | bottom | left | right | lower-left | lower-right

                硬性规则：
                1. shots 数量 %d～%d，总时长约 %d 秒
                2. visual.type 默认 ai_image；prompt 与用户同语言，禁止 http URL，长度尽量 40～220 字
                3. 每一镜 visual.prompt 必须与用户提示词同语言，且前部出现主题实体名词
                4. visual.promptEn 可选：仅作双语备份（用户写中文时主出图仍用中文 prompt；用户写英文或明确要求英文出图时才主用）
                5. 至少一半镜头 overlay.layout=none（纯画面动效）
                6. 不要输出 audio 文件路径；audio.mode 保持为 %s
                7. %s
                8. %s
                9. 相邻镜头主体/景别要有变化，但都必须服务同一用户主题；禁止整片空洞霓虹都市
                """.formatted(
                style,
                lang,
                aspect,
                cmd.getTargetDurationSec(),
                style,
                audio,
                minS, maxS,
                cmd.getTargetDurationSec(),
                audio,
                narrationRule(cmd.getAudioMode()),
                languagePolicy(lang)
        );
    }
}
