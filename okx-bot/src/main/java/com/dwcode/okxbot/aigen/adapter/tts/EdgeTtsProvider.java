package com.dwcode.okxbot.aigen.adapter.tts;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.port.TtsCommand;
import com.dwcode.okxbot.aigen.port.TtsPort;
import com.dwcode.okxbot.aigen.port.TtsResult;
import com.dwcode.okxbot.aigen.service.StoryboardNormalizeService;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.util.ProcessExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Edge TTS（Microsoft 在线神经语音，免费 CLI）。
 * 依赖：pip install edge-tts；优先 {@code edge-tts} CLI，其次 {@code python -m edge_tts}。
 * <p>
 * 文案经 UTF-8 临时文件 + {@code --file} 传入，避免 Windows 下超长 {@code --text}
 * 及偶发 {@code NoAudioReceived}；失败自动短重试。
 */
@Slf4j
@RequiredArgsConstructor
public class EdgeTtsProvider implements TtsPort {

    private static final int MAX_TEXT_LEN = 480;
    private static final int MAX_ATTEMPTS = 3;

    private final AigenProperties aigenProperties;
    private final ProcessExecutor processExecutor;
    private final AudioDurationHelper durationHelper;

    @Override
    public TtsResult synthesize(TtsCommand command) throws Exception {
        String text = sanitize(command.getText());
        if (text.isEmpty()) {
            throw new BusinessException("TTS 文本为空: scene=" + command.getSceneId());
        }
        if (text.length() > MAX_TEXT_LEN) {
            text = text.substring(0, MAX_TEXT_LEN);
            // 尽量在句号处截断
            int cut = Math.max(text.lastIndexOf('。'), text.lastIndexOf('.'));
            if (cut > MAX_TEXT_LEN / 2) {
                text = text.substring(0, cut + 1);
            }
            log.warn("Edge-TTS 文案过长已截断: scene={}, len={}", command.getSceneId(), text.length());
        }

        Path out = command.getOutputFile();
        if (out == null) {
            throw new BusinessException("TTS 输出路径为空");
        }
        if (!out.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp3")) {
            out = out.getParent().resolve(command.getSceneId() + ".mp3");
        }
        Files.createDirectories(out.getParent());

        String voice = command.getVoiceId() != null && !command.getVoiceId().isBlank()
                ? command.getVoiceId().trim()
                : aigenProperties.getTts().getDefaultVoice();
        int timeout = Math.max(30, aigenProperties.getTts().getTimeoutSeconds());

        Path textFile = out.getParent().resolve(command.getSceneId() + ".tts.txt");
        Files.writeString(textFile, text, StandardCharsets.UTF_8);

        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Files.deleteIfExists(out);
            try {
                List<String> cmd = buildCommand(voice, textFile, out);
                log.info("Edge-TTS: scene={}, voice={}, attempt={}/{}, chars={}, out={}",
                        command.getSceneId(), voice, attempt, MAX_ATTEMPTS, text.length(), out.getFileName());
                processExecutor.execute(cmd, timeout);

                if (!Files.isRegularFile(out) || Files.size(out) < 64) {
                    throw new BusinessException("Edge-TTS 未生成有效音频文件");
                }

                long fallback = StoryboardNormalizeService.estimateNarrationMs(
                        text, command.getFallbackDurationFrames(), command.getFps());
                long ms = durationHelper.probeDurationMs(out, fallback);
                String rel = "assets/audio/" + command.getSceneId() + ".mp3";
                // 成功后可删文案缓存；保留便于排查也可
                try {
                    Files.deleteIfExists(textFile);
                } catch (Exception ignored) {
                    // ignore
                }
                return TtsResult.builder()
                        .relativeSrc(rel)
                        .durationMs(ms)
                        .mock(false)
                        .build();
            } catch (Exception e) {
                last = e;
                String msg = e.getMessage() != null ? e.getMessage() : "";
                boolean retryable = msg.contains("NoAudioReceived")
                        || msg.contains("no audio")
                        || msg.contains("WebSocket")
                        || msg.contains("timeout")
                        || msg.contains("timed out")
                        || msg.contains("503")
                        || msg.contains("429")
                        || msg.contains("Connection");
                log.warn("Edge-TTS 失败 scene={} attempt={}/{}: {}",
                        command.getSceneId(), attempt, MAX_ATTEMPTS, truncate(msg, 220));
                if (!retryable || attempt >= MAX_ATTEMPTS) {
                    break;
                }
                // 退避：微软接口偶发限流 / 空包
                long sleepMs = 400L * attempt + ThreadLocalRandom.current().nextLong(200);
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
        }

        String detail = last != null ? truncate(last.getMessage(), 400) : "unknown";
        throw new BusinessException(
                "Edge-TTS 合成失败（scene=" + command.getSceneId()
                        + "）。多为微软接口瞬时无音频/网络问题。可重试任务，或将 aigen.tts.provider 设为 windows。"
                        + " 详情: " + detail);
    }

    /** 规范化口播：去控制符、压缩空白，降低服务端拒识概率 */
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') {
                sb.append(' ');
            } else if (c < 0x20 || c == 0x7f) {
                // 跳过控制字符
            } else {
                sb.append(c);
            }
        }
        return sb.toString().replaceAll("\\s{2,}", " ").trim();
    }

    private List<String> buildCommand(String voice, Path textFile, Path out) {
        AigenProperties.Tts tts = aigenProperties.getTts();
        String mode = tts.getEdgeMode() != null ? tts.getEdgeMode().trim().toLowerCase(Locale.ROOT) : "auto";
        Path absOut = out.toAbsolutePath().normalize();
        Path absText = textFile.toAbsolutePath().normalize();

        if ("python".equals(mode)) {
            return pythonModuleCmd(tts, voice, absText, absOut);
        }
        return cliCmd(tts, voice, absText, absOut);
    }

    private static List<String> cliCmd(AigenProperties.Tts tts, String voice, Path textFile, Path abs) {
        List<String> cli = new ArrayList<>();
        cli.add(blank(tts.getEdgeCommand()) ? "edge-tts" : tts.getEdgeCommand().trim());
        cli.add("--voice");
        cli.add(voice);
        // 用文件传文案，避免超长/中文参数边界问题
        cli.add("--file");
        cli.add(textFile.toString());
        cli.add("--write-media");
        cli.add(abs.toString());
        return cli;
    }

    private static List<String> pythonModuleCmd(AigenProperties.Tts tts, String voice, Path textFile, Path abs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolvePythonBinary(tts));
        cmd.add("-m");
        cmd.add("edge_tts");
        cmd.add("--voice");
        cmd.add(voice);
        cmd.add("--file");
        cmd.add(textFile.toString());
        cmd.add("--write-media");
        cmd.add(abs.toString());
        return cmd;
    }

    private static boolean looksLikeExplicitPython(String pythonPath) {
        if (pythonPath == null || pythonPath.isBlank()) {
            return false;
        }
        String p = pythonPath.trim();
        String lower = p.toLowerCase(Locale.ROOT);
        if ("python".equals(lower) || "python3".equals(lower) || "py".equals(lower)) {
            return false;
        }
        return p.contains("/") || p.contains("\\") || lower.endsWith(".exe");
    }

    private static String resolvePythonBinary(AigenProperties.Tts tts) {
        if (!blank(tts.getPythonPath()) && looksLikeExplicitPython(tts.getPythonPath())) {
            return tts.getPythonPath().trim();
        }
        return blank(tts.getPythonPath()) ? "python" : tts.getPythonPath().trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 探测 edge-tts 是否可用（轻量） */
    public boolean isAvailable() {
        AigenProperties.Tts tts = aigenProperties.getTts();
        String mode = tts.getEdgeMode() != null ? tts.getEdgeMode().trim().toLowerCase(Locale.ROOT) : "auto";

        if (!"python".equals(mode)) {
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(blank(tts.getEdgeCommand()) ? "edge-tts" : tts.getEdgeCommand().trim());
                cmd.add("--version");
                processExecutor.execute(cmd, 15);
                log.info("Edge-TTS CLI 可用");
                return true;
            } catch (Exception e) {
                log.debug("Edge-TTS CLI 不可用: {}", e.getMessage());
                if ("cli".equals(mode)) {
                    return false;
                }
            }
        }

        if (!"cli".equals(mode)) {
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(resolvePythonBinary(tts));
                cmd.add("-m");
                cmd.add("edge_tts");
                cmd.add("--version");
                processExecutor.execute(cmd, 15);
                log.info("Edge-TTS python 模块可用");
                return true;
            } catch (Exception e) {
                log.debug("Edge-TTS python 模块不可用: {}", e.getMessage());
            }
        }
        return false;
    }
}
