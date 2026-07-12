package com.dwcode.okxbot.video.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 视频核心内容提取模块配置。
 *
 * <pre>
 * video:
 *   work-dir: ./data/video
 *   yt-dlp-path: yt-dlp
 *   ffmpeg-path: ffmpeg
 *   whisper:
 *     base-url: http://127.0.0.1:8000
 *     model: medium
 *     timeout-seconds: 600
 *   llm:
 *     provider: nvidia          # 复用 ai.providers 中的 key，为空则用 default
 *     model:                   # 为空则用该供应商第一个模型
 *     temperature: 0.3
 *     max-tokens: 4096
 *   download:
 *     max-duration-seconds: 7200
 *     audio-format: mp3
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "video")
public class VideoProperties {

    /** 工作目录：下载视频、音频、临时文件 */
    private String workDir = "./data/video";

    /** yt-dlp 可执行文件路径（PATH 中或绝对路径） */
    private String ytDlpPath = "yt-dlp";

    /** FFmpeg 可执行文件路径 */
    private String ffmpegPath = "ffmpeg";

    /**
     * 是否在处理完成后清理中间媒体文件。
     * v2 默认 false，保留视频/音频/转录文件供前端查看与下载。
     */
    private boolean cleanupMedia = false;

    private Whisper whisper = new Whisper();
    private Llm llm = new Llm();
    private Download download = new Download();

    @Data
    public static class Whisper {
        /** faster-whisper / OpenAI 兼容转录服务地址 */
        private String baseUrl = "http://127.0.0.1:8000";
        /** 模型名提示（部分服务支持） */
        private String model = "medium";
        /** 请求超时（秒），长视频转录需要较长时间 */
        private int timeoutSeconds = 600;
        /** 语言，空表示自动检测 */
        private String language = "zh";
    }

    @Data
    public static class Llm {
        /** 供应商标识，对应 ai.providers 的 key；空则用 ai.default-provider */
        private String provider;
        /** 模型 ID；空则用供应商第一个模型 */
        private String model;
        private double temperature = 0.3;
        private int maxTokens = 4096;
        /**
         * 瞬时错误（503/429/网络抖动）最大重试次数（不含首次请求）。
         * NVIDIA NIM 免费档常出现 All workers are busy。
         */
        private int maxRetries = 5;
        /** 首次重试等待毫秒，之后指数退避 */
        private long retryBackoffMs = 3000;
        /** 单次退避上限毫秒 */
        private long retryMaxBackoffMs = 60000;
    }

    @Data
    public static class Download {
        /** 最长允许视频时长（秒），0 表示不限制 */
        private int maxDurationSeconds = 7200;
        /** 提取音频格式 */
        private String audioFormat = "mp3";
        /** 下载超时（秒） */
        private int timeoutSeconds = 600;
    }
}
