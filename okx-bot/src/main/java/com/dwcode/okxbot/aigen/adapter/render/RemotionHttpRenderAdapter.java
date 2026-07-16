package com.dwcode.okxbot.aigen.adapter.render;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.domain.AudioTrackDto;
import com.dwcode.okxbot.aigen.domain.StoryboardDto;
import com.dwcode.okxbot.aigen.port.RenderCommand;
import com.dwcode.okxbot.aigen.port.RenderResult;
import com.dwcode.okxbot.aigen.port.VideoRenderPort;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 调用 aigen-remotion HTTP 渲染服务。
 * <p>
 * 音频必须用 http(s) URL（Remotion 不支持 file:// 本地路径）。
 * Java 与 Node 双侧都会把相对路径改写成 http://127.0.0.1:3100/media/{taskId}/...
 */
@Slf4j
@RequiredArgsConstructor
public class RemotionHttpRenderAdapter implements VideoRenderPort {

    private final AigenProperties aigenProperties;
    private final ObjectMapper objectMapper;
    private final RemotionProcessManager remotionProcessManager;

    @Override
    public RenderResult render(RenderCommand command) throws Exception {
        remotionProcessManager.ensureRunning();

        long t0 = System.currentTimeMillis();
        AigenProperties.Remotion cfg = aigenProperties.getRemotion();
        int timeoutSec = Math.max(30, cfg.getTimeoutSeconds());

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(timeoutSec + 30L, TimeUnit.SECONDS)
                .build();

        String outputName = command.getOutputFileName() != null ? command.getOutputFileName() : "output.mp4";
        Path workDir = command.getWorkDir().toAbsolutePath().normalize();
        Path outputPath = workDir.resolve(outputName);

        Object source = command.getInputProps() != null
                ? command.getInputProps()
                : command.getStoryboard();
        if (source == null) {
            throw new BusinessException("渲染 inputProps / storyboard 为空");
        }
        ObjectNode inputProps = objectMapper.valueToTree(source);
        // 禁止下发 workDir / 本地绝对路径，避免模板拼 file://
        inputProps.remove("workDir");
        if (command.getStoryboard() != null) {
            rewriteAudioTracksToHttp(inputProps, workDir, cfg.getBaseUrl(), command.getStoryboard());
        }
        // Visual Timeline：镜头主视觉 / 口播 / BGM 相对路径 → HTTP
        rewriteVisualAssetsToHttp(inputProps, workDir, cfg.getBaseUrl());
        rewriteShotlistBgmToHttp(inputProps, workDir, cfg.getBaseUrl());
        rewriteShotAudioToHttp(inputProps, workDir, cfg.getBaseUrl());

        Map<String, Object> body = new HashMap<>();
        body.put("jobId", command.getJobId());
        body.put("compositionId", command.getCompositionId());
        body.put("inputProps", inputProps);
        body.put("workDir", workDir.toString());
        body.put("outputFile", outputPath.toString());
        body.put("codec", cfg.getCodec());
        body.put("crf", cfg.getCrf());

        String json = objectMapper.writeValueAsString(body);
        log.info("Remotion 请求 composition={}, hasShots={}",
                command.getCompositionId(), inputProps.has("shots"));

        String url = trimSlash(cfg.getBaseUrl()) + "/render";
        Request.Builder rb = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .addHeader("Content-Type", "application/json");
        if (cfg.getRenderToken() != null && !cfg.getRenderToken().isBlank()) {
            rb.addHeader("X-Aigen-Render-Token", cfg.getRenderToken());
        }

        log.info("调用 Remotion 渲染: jobId={}, url={}, composition={}",
                command.getJobId(), url, command.getCompositionId());

        try (Response response = client.newCall(rb.build()).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new BusinessException("渲染服务 HTTP " + response.code() + ": " + truncate(resp, 500));
            }
            JsonNode node = objectMapper.readTree(resp);
            boolean ok = node.path("success").asBoolean(false);
            if (!ok) {
                String err = node.path("error").asText("unknown render error");
                throw new BusinessException("渲染失败: " + truncate(err, 600));
            }
            String out = node.path("outputFile").asText(outputPath.toString());
            if (!Files.isRegularFile(Path.of(out))) {
                throw new BusinessException("渲染成功但未找到输出文件: " + out);
            }
            return RenderResult.builder()
                    .success(true)
                    .outputAbsolutePath(out)
                    .renderDurationMs(System.currentTimeMillis() - t0)
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("无法连接渲染服务: " + e.getMessage());
        }
    }

    /**
     * 相对路径 → http://127.0.0.1:3100/media/{taskId}/assets/audio/xx.wav
     */
    private void rewriteAudioTracksToHttp(ObjectNode inputProps, Path workDir, String remotionBaseUrl,
                                          StoryboardDto sb) {
        if (sb == null || sb.getAudio() == null || sb.getAudio().getTracks() == null) {
            return;
        }
        String taskId = workDir.getFileName() != null ? workDir.getFileName().toString() : "";
        String base = trimSlash(remotionBaseUrl) + "/media/" + taskId;

        ObjectNode audioObj;
        if (inputProps.get("audio") instanceof ObjectNode o) {
            audioObj = o;
        } else {
            audioObj = inputProps.putObject("audio");
        }
        ArrayNode tracks = audioObj.putArray("tracks");
        for (AudioTrackDto tr : sb.getAudio().getTracks()) {
            ObjectNode t = tracks.addObject();
            t.put("sceneId", tr.getSceneId() != null ? tr.getSceneId() : "");
            t.put("mock", tr.isMock());
            if (tr.getDurationMs() != null) {
                t.put("durationMs", tr.getDurationMs());
            }
            String rel = tr.getSrc();
            if (rel == null || rel.isBlank() || tr.isMock()
                    || rel.toLowerCase().endsWith(".txt") || rel.toLowerCase().contains("mock")) {
                t.put("mock", true);
                continue;
            }
            // 若误传绝对路径，裁成相对 workDir
            Path maybe = Path.of(rel);
            if (maybe.isAbsolute()) {
                try {
                    rel = workDir.relativize(maybe.normalize()).toString().replace('\\', '/');
                } catch (Exception e) {
                    t.put("mock", true);
                    continue;
                }
            }
            rel = rel.replace('\\', '/');
            if (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
            Path file = workDir.resolve(rel).normalize();
            if (!Files.isRegularFile(file)) {
                log.warn("音频文件不存在，跳过: {}", file);
                t.put("mock", true);
                continue;
            }
            String http = base + "/" + rel;
            t.put("src", http);
            t.put("mock", false);
            log.debug("音频 URL: scene={} -> {}", tr.getSceneId(), http);
        }
    }

    /**
     * shotlist.shots[].visual.assetPath → assetUrl (http media)
     */
    private void rewriteVisualAssetsToHttp(ObjectNode inputProps, Path workDir, String remotionBaseUrl) {
        JsonNode shots = inputProps.get("shots");
        if (!(shots instanceof ArrayNode arr) || arr.isEmpty()) {
            return;
        }
        String taskId = workDir.getFileName() != null ? workDir.getFileName().toString() : "";
        String base = trimSlash(remotionBaseUrl) + "/media/" + taskId;
        for (JsonNode shot : arr) {
            if (!(shot instanceof ObjectNode shotObj)) {
                continue;
            }
            JsonNode visual = shotObj.get("visual");
            if (!(visual instanceof ObjectNode vObj)) {
                continue;
            }
            String rel = vObj.path("assetPath").asText(null);
            if (rel == null || rel.isBlank()) {
                continue;
            }
            rel = rel.replace('\\', '/');
            if (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
            Path file = workDir.resolve(rel).normalize();
            if (!file.startsWith(workDir.normalize()) || !Files.isRegularFile(file)) {
                log.warn("视觉素材不存在，跳过: {}", file);
                continue;
            }
            vObj.put("assetUrl", base + "/" + rel);
            vObj.put("assetPath", rel);
        }
    }

    private void rewriteShotlistBgmToHttp(ObjectNode inputProps, Path workDir, String remotionBaseUrl) {
        JsonNode audio = inputProps.get("audio");
        if (!(audio instanceof ObjectNode audioObj)) {
            return;
        }
        String rel = audioObj.path("bgmSrc").asText(null);
        if (rel == null || rel.isBlank()) {
            return;
        }
        String taskId = workDir.getFileName() != null ? workDir.getFileName().toString() : "";
        String base = trimSlash(remotionBaseUrl) + "/media/" + taskId;
        rel = rel.replace('\\', '/');
        if (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        Path file = workDir.resolve(rel).normalize();
        if (Files.isRegularFile(file)) {
            audioObj.put("bgmUrl", base + "/" + rel);
        }
    }

    /** shots[].audioSrc → audioUrl */
    private void rewriteShotAudioToHttp(ObjectNode inputProps, Path workDir, String remotionBaseUrl) {
        JsonNode shots = inputProps.get("shots");
        if (!(shots instanceof ArrayNode arr) || arr.isEmpty()) {
            return;
        }
        String taskId = workDir.getFileName() != null ? workDir.getFileName().toString() : "";
        String base = trimSlash(remotionBaseUrl) + "/media/" + taskId;
        for (JsonNode shot : arr) {
            if (!(shot instanceof ObjectNode shotObj)) {
                continue;
            }
            String rel = shotObj.path("audioSrc").asText(null);
            if (rel == null || rel.isBlank()) {
                continue;
            }
            rel = rel.replace('\\', '/');
            if (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
            Path file = workDir.resolve(rel).normalize();
            if (!file.startsWith(workDir.normalize()) || !Files.isRegularFile(file)) {
                continue;
            }
            shotObj.put("audioUrl", base + "/" + rel);
            shotObj.put("audioSrc", rel);
        }
    }

    private static String trimSlash(String base) {
        if (base == null || base.isBlank()) {
            return "http://127.0.0.1:3100";
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
