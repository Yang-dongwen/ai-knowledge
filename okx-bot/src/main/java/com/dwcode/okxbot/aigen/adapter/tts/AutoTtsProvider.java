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
 */
@Slf4j
@RequiredArgsConstructor
public class AutoTtsProvider implements TtsPort {

    private final AigenProperties aigenProperties;
    private final EdgeTtsProvider edgeTtsProvider;
    private final WindowsSapiTtsProvider windowsSapiTtsProvider;
    private final MockTtsProvider mockTtsProvider;

    private volatile TtsPort resolved;
    private volatile String resolvedName;

    @Override
    public TtsResult synthesize(TtsCommand command) throws Exception {
        return resolve().synthesize(command);
    }

    public String resolvedProviderName() {
        resolve();
        return resolvedName;
    }

    private TtsPort resolve() {
        if (resolved != null) {
            return resolved;
        }
        synchronized (this) {
            if (resolved != null) {
                return resolved;
            }
            String p = aigenProperties.getTts().getProvider();
            if (p == null || p.isBlank()) {
                p = "auto";
            }
            p = p.trim().toLowerCase();
            switch (p) {
                case "mock" -> {
                    resolved = mockTtsProvider;
                    resolvedName = "mock";
                }
                case "edge" -> {
                    if (!edgeTtsProvider.isAvailable()) {
                        throw new BusinessException(
                                "Edge-TTS 不可用：请执行 pip install edge-tts，并确保 edge-tts 或 python -m edge_tts 可用");
                    }
                    resolved = edgeTtsProvider;
                    resolvedName = "edge";
                }
                case "windows", "sapi", "win" -> {
                    if (!windowsSapiTtsProvider.isAvailable()) {
                        throw new BusinessException("Windows SAPI 仅可在 Windows 上使用");
                    }
                    resolved = windowsSapiTtsProvider;
                    resolvedName = "windows";
                }
                case "auto" -> {
                    if (edgeTtsProvider.isAvailable()) {
                        resolved = edgeTtsProvider;
                        resolvedName = "edge";
                    } else if (windowsSapiTtsProvider.isAvailable()) {
                        resolved = windowsSapiTtsProvider;
                        resolvedName = "windows";
                        log.warn("Edge-TTS 不可用，回退 Windows SAPI（中文质量依赖系统语音包）");
                    } else if (aigenProperties.getTts().isFailOpenToMock()) {
                        resolved = mockTtsProvider;
                        resolvedName = "mock";
                        log.warn("无可用 TTS，fail-open-to-mock=true，使用 mock");
                    } else {
                        throw new BusinessException(
                                "无可用 TTS。请安装: pip install edge-tts，或将 aigen.tts.provider 设为 windows/mock");
                    }
                }
                default -> throw new BusinessException("未知 aigen.tts.provider: " + p);
            }
            log.info("Aigen TTS 选用实现: {}", resolvedName);
            return resolved;
        }
    }
}
