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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Edge TTS（Microsoft 在线神经语音，免费 CLI）。
 * 依赖：pip install edge-tts；优先 {@code edge-tts} CLI，其次 {@code python -m edge_tts}。
 */
@Slf4j
@RequiredArgsConstructor
public class EdgeTtsProvider implements TtsPort {

    private final AigenProperties aigenProperties;
    private final ProcessExecutor processExecutor;
    private final AudioDurationHelper durationHelper;

    @Override
    public TtsResult synthesize(TtsCommand command) throws Exception {
        String text = command.getText() != null ? command.getText().trim() : "";
        if (text.isEmpty()) {
            throw new BusinessException("TTS 文本为空: scene=" + command.getSceneId());
        }
        if (text.length() > 500) {
            text = text.substring(0, 500);
        }

        Path out = command.getOutputFile();
        if (out == null) {
            throw new BusinessException("TTS 输出路径为空");
        }
        // 统一 mp3
        if (!out.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp3")) {
            out = out.getParent().resolve(command.getSceneId() + ".mp3");
        }
        Files.createDirectories(out.getParent());
        Files.deleteIfExists(out);

        String voice = command.getVoiceId() != null && !command.getVoiceId().isBlank()
                ? command.getVoiceId()
                : aigenProperties.getTts().getDefaultVoice();
        int timeout = Math.max(30, aigenProperties.getTts().getTimeoutSeconds());

        List<String> cmd = buildCommand(voice, text, out);
        log.info("Edge-TTS: scene={}, voice={}, out={}, cmd0={}",
                command.getSceneId(), voice, out.getFileName(), cmd.isEmpty() ? "?" : cmd.get(0));
        processExecutor.execute(cmd, timeout);

        if (!Files.isRegularFile(out) || Files.size(out) < 64) {
            throw new BusinessException("Edge-TTS 未生成有效音频文件");
        }

        long fallback = StoryboardNormalizeService.estimateNarrationMs(
                text, command.getFallbackDurationFrames(), command.getFps());
        long ms = durationHelper.probeDurationMs(out, fallback);
        String rel = "assets/audio/" + command.getSceneId() + ".mp3";
        return TtsResult.builder()
                .relativeSrc(rel)
                .durationMs(ms)
                .mock(false)
                .build();
    }

    private List<String> buildCommand(String voice, String text, Path out) {
        AigenProperties.Tts tts = aigenProperties.getTts();
        String mode = tts.getEdgeMode() != null ? tts.getEdgeMode().trim().toLowerCase(Locale.ROOT) : "auto";
        Path abs = out.toAbsolutePath().normalize();

        if ("python".equals(mode)) {
            return pythonModuleCmd(tts, voice, text, abs);
        }
        // auto / cli：优先 edge-tts 独立命令（Scripts\edge-tts.exe）
        // 不再因 yml 写了 python-path: python 就强制 -m（会撞脏 PATH / 假 python）
        return cliCmd(tts, voice, text, abs);
    }

    private static List<String> cliCmd(AigenProperties.Tts tts, String voice, String text, Path abs) {
        List<String> cli = new ArrayList<>();
        cli.add(blank(tts.getEdgeCommand()) ? "edge-tts" : tts.getEdgeCommand().trim());
        cli.add("--voice");
        cli.add(voice);
        cli.add("--text");
        cli.add(text);
        cli.add("--write-media");
        cli.add(abs.toString());
        return cli;
    }

    private static List<String> pythonModuleCmd(AigenProperties.Tts tts, String voice, String text, Path abs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolvePythonBinary(tts));
        cmd.add("-m");
        cmd.add("edge_tts");
        cmd.add("--voice");
        cmd.add(voice);
        cmd.add("--text");
        cmd.add(text);
        cmd.add("--write-media");
        cmd.add(abs.toString());
        return cmd;
    }

    /**
     * 仅当配置了「像真实路径」的 python 时才强制 python 模式；
     * 配置成 {@code python} / 空 不算强制。
     */
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

    /** 探测 edge-tts 是否可用（轻量） */
    public boolean isAvailable() {
        AigenProperties.Tts tts = aigenProperties.getTts();
        String mode = tts.getEdgeMode() != null ? tts.getEdgeMode().trim().toLowerCase(Locale.ROOT) : "auto";

        // 先试 CLI
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

        // 再试 python -m edge_tts
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
