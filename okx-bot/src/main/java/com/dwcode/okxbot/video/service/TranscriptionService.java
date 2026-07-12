package com.dwcode.okxbot.video.service;

import com.dwcode.okxbot.video.client.WhisperClient;
import com.dwcode.okxbot.video.dto.TranscriptionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;

/**
 * 音频转录业务服务。
 *
 * 委托 {@link WhisperClient} 调用本地 Whisper 微服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscriptionService {

    private final WhisperClient whisperClient;

    /**
     * 转录本地音频文件。
     *
     * @param audioPath 音频绝对路径
     * @param language  语言代码，可空
     */
    public TranscriptionResult transcribe(String audioPath, String language) {
        log.info("开始转录: path={}, language={}", audioPath, language);
        TranscriptionResult result = whisperClient.transcribe(new File(audioPath), language);
        log.info("转录完成: duration={}s, segments={}",
                result.getDurationSeconds(),
                result.getSegments() != null ? result.getSegments().size() : 0);
        return result;
    }
}
