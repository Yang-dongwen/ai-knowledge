package com.dwcode.okxbot.video.service;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.client.LlmChatClient;
import com.dwcode.okxbot.video.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 内容总结与 repurpose 脚本生成服务。
 *
 * 通过 {@link LlmChatClient} 调用 LLM，要求结构化 JSON 输出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummarizationService {

    private static final String SYSTEM_PROMPT = """
            你是专业的视频内容分析师，擅长从字幕中提取核心要点，并生成适合自媒体二次创作的内容。
            你必须只输出合法 JSON，不要包含 markdown 代码围栏，不要输出 JSON 以外的解释文字。
            """;

    private final LlmChatClient llmChatClient;
    private final ObjectMapper objectMapper;

    /**
     * 基于转录结果生成结构化摘要。
     *
     * @param llmProvider 可空，任务级供应商
     * @param llmModel    可空，任务级模型
     */
    public VideoSummaryPart summarize(String title,
                                      TranscriptionResult transcription,
                                      boolean extractMindMap,
                                      boolean generateRepurposeScript,
                                      String language,
                                      String llmProvider,
                                      String llmModel) {
        String userPrompt = buildUserPrompt(title, transcription, extractMindMap, generateRepurposeScript, language);
        log.info("开始 LLM 总结: title={}, provider={}, model={}, transcriptLen={}",
                title, llmProvider, llmModel,
                transcription.getText() != null ? transcription.getText().length() : 0);

        String raw = llmChatClient.chat(SYSTEM_PROMPT, userPrompt, llmProvider, llmModel);
        VideoSummaryPart part = parseSummaryJson(raw);

        if (!extractMindMap) {
            part.setMindMapMarkdown(null);
        }
        if (!generateRepurposeScript) {
            part.setRepurposeScript(null);
        }

        log.info("LLM 总结完成: keyPoints={}, chapters={}",
                part.getKeyPoints() != null ? part.getKeyPoints().size() : 0,
                part.getChapters() != null ? part.getChapters().size() : 0);
        return part;
    }

    private String buildUserPrompt(String title,
                                   TranscriptionResult transcription,
                                   boolean extractMindMap,
                                   boolean generateRepurposeScript,
                                   String language) {
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

        // 控制 prompt 长度，过长截断中间保留头尾
        String transcriptBody = segmentsText.toString();
        final int maxChars = 24000;
        if (transcriptBody.length() > maxChars) {
            int head = maxChars * 2 / 3;
            int tail = maxChars - head;
            transcriptBody = transcriptBody.substring(0, head)
                    + "\n\n...[中间字幕已省略]...\n\n"
                    + transcriptBody.substring(transcriptBody.length() - tail);
        }

        String langHint = (language != null && language.toLowerCase(Locale.ROOT).startsWith("en"))
                ? "Please respond in English."
                : "请使用中文输出。";

        return """
                请分析以下视频字幕，输出 JSON，字段如下：
                {
                  "keyPoints": [{"timestamp": "00:01:23", "point": "核心观点"}],
                  "chapters": [{"timestamp": "00:00:00", "title": "章节标题", "summary": "章节摘要"}],
                  "mindMapMarkdown": "Markdown 思维导图（仅当需要时填写）",
                  "repurposeScript": "适合发 X/Twitter 的文案，含 hook 与价值点（仅当需要时填写）"
                }

                要求：
                1. keyPoints 提取 5-12 条核心要点，timestamp 尽量对应字幕时间
                2. chapters 按逻辑划分 3-8 个章节
                3. mindMapMarkdown 使用 Markdown 列表或 mermaid mindmap 均可
                4. repurposeScript 适合短内容平台，带开头 hook，控制在 500 字内
                5. extractMindMap=%s, generateRepurposeScript=%s
                6. %s

                视频标题：%s

                带时间戳字幕：
                %s
                """.formatted(
                extractMindMap,
                generateRepurposeScript,
                langHint,
                title != null ? title : "未知",
                transcriptBody
        );
    }

    private VideoSummaryPart parseSummaryJson(String raw) {
        String json = extractJson(raw);
        try {
            JsonNode root = objectMapper.readTree(json);
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
            return part;
        } catch (Exception e) {
            log.error("解析 LLM 总结 JSON 失败, raw={}", truncate(raw, 500), e);
            // 兜底：把原文塞进 repurposeScript，避免整任务失败
            VideoSummaryPart fallback = new VideoSummaryPart();
            KeyPointDto kp = new KeyPointDto();
            kp.setTimestamp("00:00:00");
            kp.setPoint("模型未返回合法 JSON，已保留原文摘要片段");
            fallback.getKeyPoints().add(kp);
            fallback.setRepurposeScript(truncate(raw, 2000));
            return fallback;
        }
    }

    /**
     * 从可能带 ```json 围栏的文本中提取 JSON 对象。
     */
    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("LLM 返回为空");
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNl = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                text = text.substring(firstNl + 1, lastFence).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
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

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
