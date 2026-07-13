package com.dwcode.okxbot.aigen.adapter.tts;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.port.TtsCommand;
import com.dwcode.okxbot.aigen.port.TtsPort;
import com.dwcode.okxbot.aigen.port.TtsResult;
import com.dwcode.okxbot.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 按配置选择 TTS：edge / windows / mock / auto。
 * <p>
 * {@code auto}：优先 Edge；单次合成失败时回退 Windows SAPI（再可选 mock），
 * 避免微软接口瞬时 NoAudioReceived 导致整任务失败。
 */
@Slf4j
@RequiredArgsConstructor
public class AutoTtsProvider implements TtsPort {

    private final AigenProperties aigenProperties;
    private final EdgeTtsProvider edgeTtsProvider;
    private final WindowsSapiTtsProvider windowsSapiTtsProvider;
    private final MockTtsProvider mockTtsProvider;

    private volatile String preferredName;

    @Override
    public TtsResult synthesize(TtsCommand command) throws Exception {
        String mode = preferredMode();
        switch (mode) {
            case "mock" -> {
                return mockTtsProvider.synthesize(command);
            }
            case "edge" -> {
                return edgeTtsProvider.synthesize(command);
            }
            case "windows", "sapi", "win" -> {
                return windowsSapiTtsProvider.synthesize(command);
            }
            case "auto" -> {
                return synthesizeAuto(command);
            }
            default -> throw new BusinessException("未知 aigen.tts.provider: " + mode);
        }
    }

    private TtsResult synthesizeAuto(TtsCommand command) throws Exception {
        // 首选 Edge
        if (edgeTtsProvider.isAvailable()) {
            try {
                preferredName = "edge";
                return edgeTtsProvider.synthesize(command);
            } catch (Exception edgeErr) {
                log.warn("Edge-TTS 合成失败，尝试 Windows SAPI 回退: scene={}, err={}",
                        command.getSceneId(), truncate(edgeErr.getMessage(), 180));
                if (windowsSapiTtsProvider.isAvailable()) {
                    try {
                        // SAPI 常用 wav，输出路径可能是 .mp3 占位，交给 SAPI 自己改扩展名
                        TtsResult r = windowsSapiTtsProvider.synthesize(command);
                        log.info("TTS 已回退 Windows SAPI: scene={}", command.getSceneId());
                        return r;
                    } catch (Exception winErr) {
                        log.warn("Windows SAPI 也失败: {}", truncate(winErr.getMessage(), 160));
                        if (aigenProperties.getTts().isFailOpenToMock()) {
                            log.warn("fail-open-to-mock=true，scene={} 使用 mock", command.getSceneId());
                            return mockTtsProvider.synthesize(command);
                        }
                        throw new BusinessException(
                                "配音失败：Edge-TTS 与 Windows 语音均不可用。Edge: "
                                        + truncate(edgeErr.getMessage(), 200)
                                        + " | SAPI: " + truncate(winErr.getMessage(), 120));
                    }
                }
                if (aigenProperties.getTts().isFailOpenToMock()) {
                    log.warn("无 Windows SAPI，fail-open-to-mock=true，使用 mock");
                    return mockTtsProvider.synthesize(command);
                }
                throw edgeErr;
            }
        }
        if (windowsSapiTtsProvider.isAvailable()) {
            preferredName = "windows";
            log.warn("Edge-TTS 不可用，使用 Windows SAPI");
            return windowsSapiTtsProvider.synthesize(command);
        }
        if (aigenProperties.getTts().isFailOpenToMock()) {
            preferredName = "mock";
            log.warn("无可用 TTS，fail-open-to-mock=true，使用 mock");
            return mockTtsProvider.synthesize(command);
        }
        throw new BusinessException(
                "无可用 TTS。请安装: pip install edge-tts，或将 aigen.tts.provider 设为 windows/mock");
    }

    public String resolvedProviderName() {
        if (preferredName != null) {
            return preferredName;
        }
        return preferredMode();
    }

    private String preferredMode() {
        String p = aigenProperties.getTts().getProvider();
        if (p == null || p.isBlank()) {
            return "auto";
        }
        return p.trim().toLowerCase();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
