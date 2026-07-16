package com.dwcode.okxbot.video.service;

import com.dwcode.okxbot.common.ai.LlmCallOptions;
import com.dwcode.okxbot.common.ai.LlmChatClient;
import com.dwcode.okxbot.common.ai.LlmContentHelper;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.dto.*;
import com.dwcode.okxbot.video.port.VisualUnderstandingResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 内容总结：ASR 分层 Digest + 可选视觉 Fuse。
 * <p>
 * 不变量：最终总结 LLM 只消费 {@link TranscriptDigest}，禁止全量字幕塞入 Fuse prompt。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummarizationService {

    private static final String SYSTEM_PROMPT = """
            你是专业的视频内容分析师，擅长从字幕与画面线索中提取核心要点，并生成适合自媒体二次创作的内容。
            你必须只输出合法 JSON 对象，不要包含 markdown 代码围栏，不要输出 JSON 以外的解释文字。
            """;

    private static final String FUSE_PROMPT_V1 = """
            融合规则（FUSE_PROMPT_V1）：
            1. 事实/数字/屏幕字：以 OCR/画面为准
            2. 观点/口播金句：以 ASR 摘要为准
            3. point 可短标注来源 [画面] / [口播]
            4. partialVisual=true 时不得假装画面已覆盖未采样区间
            """;

    private final LlmChatClient llmChatClient;
    private final ObjectMapper objectMapper;
    private final VideoProperties videoProperties;

    /**
     * 兼容旧入口：先 Digest 再 summarizeFromDigest。
     */
    public VideoSummaryPart summarize(String title,
                                      TranscriptionResult transcription,
                                      boolean extractMindMap,
                                      boolean generateRepurposeScript,
                                      String language,
                                      String llmProvider,
                                      String llmModel) {
        TranscriptDigest digest = prepareTranscriptDigest(transcription, language, llmProvider, llmModel);
        return summarizeFromDigest(title, digest, extractMindMap, generateRepurposeScript,
                language, llmProvider, llmModel);
    }

    /**
     * ASR 分层 Map-Reduce 入口。
     */
    public TranscriptDigest prepareTranscriptDigest(TranscriptionResult asr,
                                                    String language,
                                                    String llmProvider,
                                                    String llmModel) {
        TranscriptDigest digest = new TranscriptDigest();
        if (asr == null) {
            digest.setWindowCount(0);
            digest.setMapLlmCalls(0);
            digest.setOverallText("");
            return digest;
        }

        String full = buildSegmentText(asr);
        int singleLimit = Math.max(1000, videoProperties.getUnderstanding().getDigestSingleWindowChars());
        int windowChars = Math.max(1500, videoProperties.getUnderstanding().getDigestWindowChars());

        if (full.length() <= singleLimit) {
            TranscriptDigest.DigestWindow w = new TranscriptDigest.DigestWindow();
            w.setStartSec(0);
            w.setEndSec(asr.getDurationSeconds() != null ? asr.getDurationSeconds() : 0);
            w.setPartialSummary(full.length() > 4000 ? full.substring(0, 4000) + "…" : full);
            digest.getWindows().add(w);
            digest.setWindowCount(1);
            digest.setMapLlmCalls(0);
            digest.setOverallText(w.getPartialSummary());
            return digest;
        }

        List<String> parts = splitByChars(full, windowChars);
        int mapCalls = 0;
        StringBuilder reduced = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            String partial = mapWindowSummary(part, language, llmProvider, llmModel, i, parts.size());
            mapCalls++;
            TranscriptDigest.DigestWindow w = new TranscriptDigest.DigestWindow();
            w.setStartSec(0);
            w.setEndSec(0);
            w.setPartialSummary(partial);
            digest.getWindows().add(w);
            if (reduced.length() > 0) {
                reduced.append("\n");
            }
            reduced.append(partial);
        }
        digest.setWindowCount(parts.size());
        digest.setMapLlmCalls(mapCalls);

        // reduce
        String overall = reduced.toString();
        if (overall.length() > singleLimit) {
            overall = mapWindowSummary(overall, language, llmProvider, llmModel, -1, 1);
            mapCalls++;
            digest.setMapLlmCalls(mapCalls);
        }
        // last-resort guard
        final int hard = 12000;
        if (overall.length() > hard) {
            digest.setTruncatedGuardHit(true);
            log.warn("ASR digest last-resort truncate: len={}", overall.length());
            overall = overall.substring(0, hard * 2 / 3)
                    + "\n...[digest truncated]...\n"
                    + overall.substring(overall.length() - hard / 3);
        }
        digest.setOverallText(overall);
        log.info("TranscriptDigest 完成: windows={}, mapLlmCalls={}, overallLen={}",
                digest.getWindowCount(), digest.getMapLlmCalls(), overall.length());
        return digest;
    }

    public VideoSummaryPart summarizeFromDigest(String title,
                                                TranscriptDigest digest,
                                                boolean extractMindMap,
                                                boolean generateRepurposeScript,
                                                String language,
                                                String llmProvider,
                                                String llmModel) {
        String userPrompt = buildDigestOnlyPrompt(title, digest, extractMindMap, generateRepurposeScript, language, false, null);
        return runSummaryLlm(userPrompt, extractMindMap, generateRepurposeScript, llmProvider, llmModel, title);
    }

    public VideoSummaryPart summarizeFused(String title,
                                           TranscriptDigest digest,
                                           VisualUnderstandingResult visual,
                                           boolean extractMindMap,
                                           boolean generateRepurposeScript,
                                           String language,
                                           String llmProvider,
                                           String llmModel) {
        String userPrompt = buildDigestOnlyPrompt(title, digest, extractMindMap, generateRepurposeScript,
                language, true, visual);
        VideoSummaryPart part = runSummaryLlm(userPrompt, extractMindMap, generateRepurposeScript,
                llmProvider, llmModel, title);
        applyVisualFields(part, visual);
        part.setMultimodal(true);
        return part;
    }

    private VideoSummaryPart runSummaryLlm(String userPrompt,
                                           boolean extractMindMap,
                                           boolean generateRepurposeScript,
                                           String llmProvider,
                                           String llmModel,
                                           String title) {
        log.info("开始 LLM 总结: title={}, provider={}, model={}, promptLen={}",
                title, llmProvider, llmModel, userPrompt.length());

        // hybrid Fuse 字段多，默认 4096 容易截断 chapters/mindMap/repurpose（见空 chapters + 残缺 keyPoints）
        int baseMax = videoProperties.getLlm().getMaxTokens() > 0
                ? videoProperties.getLlm().getMaxTokens() : 4096;
        int maxTokens = Math.max(baseMax, 8192);

        LlmCallOptions options = LlmCallOptions.builder()
                .temperature(videoProperties.getLlm().getTemperature())
                .maxTokens(maxTokens)
                .maxRetries(videoProperties.getLlm().getMaxRetries())
                .timeoutSeconds(180)
                .responseFormat("json_object")
                .build();

        String raw = llmChatClient.chat(SYSTEM_PROMPT, userPrompt, llmProvider, llmModel, options);
        VideoSummaryPart part = parseSummaryJson(raw);
        sanitizePart(part);

        if (!extractMindMap) {
            part.setMindMapMarkdown(null);
        }
        if (!generateRepurposeScript) {
            part.setRepurposeScript(null);
        }

        // 缺 chapters / 导图 / 二创时二次补全（避免 max_tokens 截断或模型偷懒）
        if (needsStructuralFill(part, extractMindMap, generateRepurposeScript)) {
            log.warn("总结字段不完整，触发补全: chapters={}, mindMap={}, repurpose={}",
                    part.getChapters() != null ? part.getChapters().size() : 0,
                    part.getMindMapMarkdown() != null && !part.getMindMapMarkdown().isBlank(),
                    part.getRepurposeScript() != null && !part.getRepurposeScript().isBlank());
            fillMissingStructure(part, title, extractMindMap, generateRepurposeScript,
                    llmProvider, llmModel, maxTokens);
            sanitizePart(part);
        }
        return part;
    }

    private static boolean needsStructuralFill(VideoSummaryPart part,
                                               boolean extractMindMap,
                                               boolean generateRepurposeScript) {
        if (part == null) {
            return true;
        }
        boolean noChapters = part.getChapters() == null || part.getChapters().isEmpty();
        boolean noMind = extractMindMap && (part.getMindMapMarkdown() == null || part.getMindMapMarkdown().isBlank());
        boolean noRep = generateRepurposeScript
                && (part.getRepurposeScript() == null || part.getRepurposeScript().isBlank());
        return noChapters || noMind || noRep;
    }

    /**
     * 只补 chapters / mindMap / repurpose，不重写已有 keyPoints。
     */
    private void fillMissingStructure(VideoSummaryPart part,
                                      String title,
                                      boolean extractMindMap,
                                      boolean generateRepurposeScript,
                                      String llmProvider,
                                      String llmModel,
                                      int maxTokens) {
        StringBuilder kpBrief = new StringBuilder();
        if (part.getKeyPoints() != null) {
            int n = 0;
            for (KeyPointDto kp : part.getKeyPoints()) {
                if (kp.getPoint() == null || kp.getPoint().isBlank()) {
                    continue;
                }
                kpBrief.append("- [").append(kp.getTimestamp() != null ? kp.getTimestamp() : "")
                        .append("] ").append(kp.getPoint()).append('\n');
                if (++n >= 12) {
                    break;
                }
            }
        }
        String visualHint = part.getVisualSummary() != null
                ? part.getVisualSummary()
                : "";
        if (visualHint.length() > 800) {
            visualHint = visualHint.substring(0, 800) + "…";
        }

        String user = """
                根据已有要点补全视频结构化产物，只输出 JSON：
                {
                  "chapters": [{"timestamp":"00:00","title":"章节","summary":"摘要"}],
                  "mindMapMarkdown": "Markdown 思维导图",
                  "repurposeScript": "二创短文案，含 hook，300-500 字"
                }
                硬性要求：
                1. chapters 必须 3-8 条，不可空数组
                2. extractMindMap=%s → mindMapMarkdown 必须非空（用 - 列表或 mermaid mindmap）
                3. generateRepurposeScript=%s → repurposeScript 必须非空
                4. 不要输出 keyPoints / visual* 字段
                5. 用中文

                标题：%s
                已有要点：
                %s
                画面摘要（参考）：
                %s
                """.formatted(
                extractMindMap,
                generateRepurposeScript,
                title != null ? title : "未知",
                kpBrief.isEmpty() ? "（无，请根据常识从标题推断并尽量合理分段）" : kpBrief,
                visualHint.isBlank() ? "（无）" : visualHint
        );

        LlmCallOptions options = LlmCallOptions.builder()
                .temperature(0.3)
                .maxTokens(Math.max(4096, maxTokens / 2))
                .maxRetries(videoProperties.getLlm().getMaxRetries())
                .timeoutSeconds(120)
                .responseFormat("json_object")
                .build();
        try {
            String raw = llmChatClient.chat(SYSTEM_PROMPT, user, llmProvider, llmModel, options);
            VideoSummaryPart fill = parseSummaryJson(raw);
            if (fill.getChapters() != null && !fill.getChapters().isEmpty()) {
                if (part.getChapters() == null || part.getChapters().isEmpty()) {
                    part.setChapters(fill.getChapters());
                }
            }
            if (extractMindMap && (part.getMindMapMarkdown() == null || part.getMindMapMarkdown().isBlank())
                    && fill.getMindMapMarkdown() != null && !fill.getMindMapMarkdown().isBlank()) {
                part.setMindMapMarkdown(fill.getMindMapMarkdown());
            }
            if (generateRepurposeScript
                    && (part.getRepurposeScript() == null || part.getRepurposeScript().isBlank())
                    && fill.getRepurposeScript() != null && !fill.getRepurposeScript().isBlank()) {
                part.setRepurposeScript(fill.getRepurposeScript());
            }
        } catch (Exception e) {
            log.warn("结构化补全失败: {}", e.getMessage());
        }
    }

    /** 去掉截断产生的空要点等脏数据 */
    private static void sanitizePart(VideoSummaryPart part) {
        if (part == null) {
            return;
        }
        if (part.getKeyPoints() != null) {
            part.getKeyPoints().removeIf(kp ->
                    kp == null || kp.getPoint() == null || kp.getPoint().isBlank());
        }
        if (part.getChapters() != null) {
            part.getChapters().removeIf(ch ->
                    ch == null || ((ch.getTitle() == null || ch.getTitle().isBlank())
                            && (ch.getSummary() == null || ch.getSummary().isBlank())));
        }
    }

    private void applyVisualFields(VideoSummaryPart part, VisualUnderstandingResult visual) {
        if (visual == null) {
            return;
        }
        // 画面字段由 Omni 结果写入；不依赖 Fuse LLM 再抄一遍，避免占满 max_tokens
        part.setVisualSummary(visual.getOverallVisualSummary());
        part.setPartialVisual(visual.isPartial());
        if (visual.getVisualKeyPoints() != null) {
            List<KeyPointDto> list = new ArrayList<>();
            for (var vk : visual.getVisualKeyPoints()) {
                KeyPointDto dto = new KeyPointDto();
                dto.setTimestamp(vk.getTimestamp() != null ? vk.getTimestamp() : formatTimestamp(
                        vk.getStartSec() != null ? vk.getStartSec() : 0));
                dto.setPoint(vk.getPoint());
                list.add(dto);
            }
            part.setVisualKeyPoints(list);
        }
        if (visual.getOnScreenTexts() != null) {
            List<String> ocr = new ArrayList<>();
            int n = 0;
            for (var o : visual.getOnScreenTexts()) {
                if (o.getText() != null && !o.getText().isBlank()) {
                    ocr.add((o.getTimestamp() != null ? o.getTimestamp() + " " : "") + o.getText());
                    if (++n >= 30) {
                        break;
                    }
                }
            }
            part.setOnScreenTexts(ocr);
        }
        if (visual.getScenes() != null) {
            List<String> scenes = new ArrayList<>();
            int n = 0;
            for (var s : visual.getScenes()) {
                String line = (s.getStartTimestamp() != null ? s.getStartTimestamp() : "")
                        + " " + (s.getDescription() != null ? s.getDescription() : "");
                scenes.add(line.trim());
                if (++n >= 40) {
                    break;
                }
            }
            part.setScenes(scenes);
        }
    }

    private String buildDigestOnlyPrompt(String title,
                                         TranscriptDigest digest,
                                         boolean extractMindMap,
                                         boolean generateRepurposeScript,
                                         String language,
                                         boolean fused,
                                         VisualUnderstandingResult visual) {
        String langHint = (language != null && language.toLowerCase(Locale.ROOT).startsWith("en"))
                ? "Please respond in English."
                : "请使用中文输出。";

        String digestText = serializeDigest(digest);
        // 视觉细节已在 UNDERSTANDING 阶段落库；Fuse 只给压缩线索，防止 token 被 scenes 占满
        String visualBlock = compactVisualBlock(fused, visual);

        String mindReq = extractMindMap
                ? "mindMapMarkdown 必须输出非空 Markdown 导图（- 列表或 mermaid mindmap）"
                : "mindMapMarkdown 填 null";
        String repReq = generateRepurposeScript
                ? "repurposeScript 必须输出非空二创文案（含 hook，300-500 字）"
                : "repurposeScript 填 null";

        return """
                请分析以下视频内容（字幕为分层摘要 Digest，非全量字幕），只输出一个 JSON：
                {
                  "chapters": [{"timestamp": "00:00:00", "title": "章节标题", "summary": "章节摘要"}],
                  "mindMapMarkdown": "思维导图 Markdown",
                  "repurposeScript": "二创脚本",
                  "keyPoints": [{"timestamp": "00:01:23", "point": "核心观点"}]
                }

                硬性要求（按优先级，务必先写前三项再写 keyPoints）：
                1. chapters 必须 3-8 条，禁止空数组
                2. %s
                3. %s
                4. keyPoints 5-12 条，timestamp 尽量对应内容
                5. 不要输出 visualSummary/visualKeyPoints/onScreenTexts/scenes（系统会单独合并画面结果）
                6. %s
                7. multimodalFuse=%s

                视频标题：%s

                【口播 Digest】
                %s
                %s
                """.formatted(
                mindReq,
                repReq,
                langHint,
                fused,
                title != null ? title : "未知",
                digestText,
                visualBlock
        );
    }

    /** Fuse 用压缩画面线索（非全量 scenes） */
    private String compactVisualBlock(boolean fused, VisualUnderstandingResult visual) {
        if (!fused || visual == null) {
            return "";
        }
        String summary = nullToEmpty(visual.getOverallVisualSummary());
        if (summary.length() > 1200) {
            summary = summary.substring(0, 1200) + "…";
        }
        StringBuilder scenes = new StringBuilder();
        if (visual.getScenes() != null) {
            int n = 0;
            for (var s : visual.getScenes()) {
                if (s.getDescription() == null || s.getDescription().isBlank()) {
                    continue;
                }
                scenes.append("- ").append(s.getStartTimestamp() != null ? s.getStartTimestamp() : "")
                        .append(' ').append(s.getDescription()).append('\n');
                if (++n >= 8) {
                    break;
                }
            }
        }
        StringBuilder ocr = new StringBuilder();
        if (visual.getOnScreenTexts() != null) {
            int n = 0;
            for (var o : visual.getOnScreenTexts()) {
                if (o.getText() == null || o.getText().isBlank()) {
                    continue;
                }
                ocr.append("- ").append(o.getText()).append('\n');
                if (++n >= 10) {
                    break;
                }
            }
        }
        return """

                【画面线索（已压缩，仅供融合，勿整段抄回 JSON）】
                partialVisual=%s
                overallVisualSummary:
                %s
                代表性场景（最多8条）:
                %s
                屏幕字样例（最多10条）:
                %s
                %s
                """.formatted(
                visual.isPartial(),
                summary,
                scenes.isEmpty() ? "（无）" : scenes,
                ocr.isEmpty() ? "（无）" : ocr,
                FUSE_PROMPT_V1
        );
    }

    private String serializeDigest(TranscriptDigest digest) {
        if (digest == null) {
            return "(无口播摘要)";
        }
        StringBuilder sb = new StringBuilder();
        if (digest.getOverallText() != null) {
            sb.append(digest.getOverallText());
        }
        if (digest.getWindows() != null && digest.getWindows().size() > 1) {
            sb.append("\n\n--- 分窗摘要 ---\n");
            int i = 1;
            for (TranscriptDigest.DigestWindow w : digest.getWindows()) {
                sb.append("窗").append(i++).append(": ");
                if (w.getPartialSummary() != null) {
                    sb.append(w.getPartialSummary());
                }
                sb.append('\n');
            }
        }
        String s = sb.toString();
        // 安全上限：仅 Digest 侧，不是 raw ASR
        if (s.length() > 16000) {
            return s.substring(0, 16000) + "\n...[digest prompt truncated]...";
        }
        return s.isBlank() ? "(无口播摘要)" : s;
    }

    private String mapWindowSummary(String part, String language, String provider, String model,
                                    int index, int total) {
        String lang = (language != null && language.toLowerCase(Locale.ROOT).startsWith("en"))
                ? "English" : "中文";
        String system = "你是字幕摘要助手，只输出该窗口的要点摘要纯文本，不要 JSON。";
        String user = "请用" + lang + "总结以下字幕窗口(" + (index + 1) + "/" + total + ")，200-400字：\n" + part;
        LlmCallOptions options = LlmCallOptions.builder()
                .temperature(0.2)
                .maxTokens(1024)
                .maxRetries(videoProperties.getLlm().getMaxRetries())
                .timeoutSeconds(120)
                .build();
        try {
            return llmChatClient.chat(system, user, provider, model, options);
        } catch (Exception e) {
            log.warn("digest map 失败，使用截断原文: {}", e.getMessage());
            return part.length() > 800 ? part.substring(0, 800) + "…" : part;
        }
    }

    private static List<String> splitByChars(String text, int windowChars) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int end = Math.min(text.length(), i + windowChars);
            // 尽量在换行处断开
            if (end < text.length()) {
                int nl = text.lastIndexOf('\n', end);
                if (nl > i + windowChars / 2) {
                    end = nl;
                }
            }
            parts.add(text.substring(i, end));
            i = end;
        }
        return parts;
    }

    private String buildSegmentText(TranscriptionResult transcription) {
        StringBuilder segmentsText = new StringBuilder();
        if (transcription.getSegments() != null) {
            for (TranscriptionSegment seg : transcription.getSegments()) {
                segmentsText.append('[')
                        .append(formatTimestamp(seg.getStart()))
                        .append(" - ")
                        .append(formatTimestamp(seg.getEnd()))
                        .append("] ")
                        .append(seg.getText())
                        .append('\n');
            }
        }
        if (segmentsText.isEmpty() && transcription.getText() != null) {
            segmentsText.append(transcription.getText());
        }
        return segmentsText.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private VideoSummaryPart parseSummaryJson(String raw) {
        // 优先宽松解析（兼容截断/think 噪声）
        JsonNode root = null;
        try {
            root = LlmContentHelper.parseJsonLenient(raw);
        } catch (Exception e) {
            log.debug("parseJsonLenient 失败: {}", e.getMessage());
        }
        String json = LlmContentHelper.extractJsonObjectOrRaw(raw);
        try {
            VideoSummaryPart direct = objectMapper.readValue(
                    root != null ? root.toString() : json, VideoSummaryPart.class);
            if (direct != null) {
                if (direct.getKeyPoints() == null) {
                    direct.setKeyPoints(new ArrayList<>());
                }
                if (direct.getChapters() == null) {
                    direct.setChapters(new ArrayList<>());
                }
                if (!direct.getKeyPoints().isEmpty() || !direct.getChapters().isEmpty()
                        || direct.getMindMapMarkdown() != null || direct.getRepurposeScript() != null) {
                    return direct;
                }
            }
        } catch (Exception e) {
            log.debug("VideoSummaryPart 直转失败，改字段级解析: {}", e.getMessage());
        }

        try {
            if (root == null) {
                root = objectMapper.readTree(json);
            }
            VideoSummaryPart part = new VideoSummaryPart();

            List<KeyPointDto> keyPoints = new ArrayList<>();
            JsonNode kp = root.path("keyPoints");
            if (kp.isArray()) {
                for (JsonNode n : kp) {
                    KeyPointDto dto = new KeyPointDto();
                    dto.setTimestamp(n.path("timestamp").asText(""));
                    dto.setPoint(n.path("point").asText(""));
                    keyPoints.add(dto);
                }
            }
            part.setKeyPoints(keyPoints);

            List<ChapterDto> chapters = new ArrayList<>();
            JsonNode ch = root.path("chapters");
            if (ch.isArray()) {
                for (JsonNode n : ch) {
                    ChapterDto dto = new ChapterDto();
                    dto.setTimestamp(n.path("timestamp").asText(""));
                    dto.setTitle(n.path("title").asText(""));
                    dto.setSummary(n.path("summary").asText(""));
                    chapters.add(dto);
                }
            }
            part.setChapters(chapters);

            if (root.has("mindMapMarkdown") && !root.path("mindMapMarkdown").isNull()) {
                part.setMindMapMarkdown(root.path("mindMapMarkdown").asText(null));
            }
            if (root.has("repurposeScript") && !root.path("repurposeScript").isNull()) {
                part.setRepurposeScript(root.path("repurposeScript").asText(null));
            }
            if (root.has("visualSummary") && !root.path("visualSummary").isNull()) {
                part.setVisualSummary(root.path("visualSummary").asText(null));
            }
            return part;
        } catch (Exception e) {
            log.error("解析 LLM 总结 JSON 失败, raw={}", LlmContentHelper.truncate(raw, 500), e);
            VideoSummaryPart fallback = new VideoSummaryPart();
            KeyPointDto kp = new KeyPointDto();
            kp.setTimestamp("00:00:00");
            kp.setPoint("模型未返回合法 JSON，已保留原文摘要片段");
            fallback.getKeyPoints().add(kp);
            fallback.setRepurposeScript(LlmContentHelper.truncate(raw, 2000));
            return fallback;
        }
    }

    static String formatTimestamp(double seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        int total = (int) Math.floor(seconds);
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        if (h > 0) {
            return String.format("%02d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }
}
