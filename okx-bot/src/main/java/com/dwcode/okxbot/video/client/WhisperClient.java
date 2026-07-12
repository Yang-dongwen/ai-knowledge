package com.dwcode.okxbot.video.client;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.dto.TranscriptionResult;
import com.dwcode.okxbot.video.dto.TranscriptionSegment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本地 Whisper 转录服务客户端（OpenAI 兼容 /v1/audio/transcriptions）。
 *
 * 外部调用抽离：业务层只依赖本 Client，不直接拼 HTTP。
 */
@Slf4j
@Component
public class WhisperClient {

    private final VideoProperties videoProperties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    public WhisperClient(VideoProperties videoProperties, ObjectMapper objectMapper) {
        this.videoProperties = videoProperties;
        this.objectMapper = objectMapper;
        int timeout = videoProperties.getWhisper().getTimeoutSeconds();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 上传音频文件并获取带时间戳的转录结果。
     *
     * @param audioFile 本地音频文件
     * @param language  语言代码，可空
     */
    public TranscriptionResult transcribe(File audioFile, String language) {
        if (audioFile == null || !audioFile.exists()) {
            throw new BusinessException("音频文件不存在: " + (audioFile != null ? audioFile.getAbsolutePath() : "null"));
        }

        String baseUrl = normalizeBaseUrl(videoProperties.getWhisper().getBaseUrl());
        String url = baseUrl + "/v1/audio/transcriptions";

        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.getName(),
                        RequestBody.create(audioFile, MediaType.parse("application/octet-stream")))
                .addFormDataPart("model", videoProperties.getWhisper().getModel())
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "segment");

        String lang = language != null && !language.isBlank()
                ? language
                : videoProperties.getWhisper().getLanguage();
        if (lang != null && !lang.isBlank()) {
            bodyBuilder.addFormDataPart("language", lang);
        }

        Request request = new Request.Builder()
                .url(url)
                .post(bodyBuilder.build())
                .build();

        log.info("调用 Whisper 转录: url={}, file={}, size={}KB",
                url, audioFile.getName(), audioFile.length() / 1024);

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("Whisper 转录失败: status={}, body={}", response.code(), truncate(body, 1000));
                throw new BusinessException("Whisper 转录服务失败（HTTP " + response.code() + "）: "
                        + truncate(body, 300));
            }
            return parseResponse(body);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException("无法连接 Whisper 服务（" + baseUrl + "）: " + e.getMessage());
        }
    }

    private TranscriptionResult parseResponse(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        TranscriptionResult result = new TranscriptionResult();
        result.setText(root.path("text").asText(""));
        result.setLanguage(root.path("language").asText(null));

        List<TranscriptionSegment> segments = new ArrayList<>();
        JsonNode segs = root.path("segments");
        if (segs.isArray()) {
            for (JsonNode seg : segs) {
                TranscriptionSegment s = new TranscriptionSegment();
                s.setId(seg.path("id").asInt(segments.size()));
                s.setStart(seg.path("start").asDouble(0));
                s.setEnd(seg.path("end").asDouble(0));
                s.setText(seg.path("text").asText("").trim());
                segments.add(s);
            }
        }
        result.setSegments(segments);

        // duration 可能在根节点或从最后一个 segment 推断
        if (root.has("duration")) {
            result.setDurationSeconds(root.path("duration").asDouble(0));
        } else if (!segments.isEmpty()) {
            result.setDurationSeconds(segments.get(segments.size() - 1).getEnd());
        }

        log.info("Whisper 转录完成: segments={}, textLen={}", segments.size(),
                result.getText() != null ? result.getText().length() : 0);
        return result;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://127.0.0.1:8000";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
