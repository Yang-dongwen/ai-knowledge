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

    /** 单用户进行中 + 排队任务上限 */
    private int maxConcurrentTasksPerUser = 2;

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
    /** 多模态画面理解（默认 audio_only 兼容现网） */
    private Understanding understanding = new Understanding();

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
        /**
         * 由 Spring Boot 托管启动/停止本地 whisper-service 进程。
         * 若启动前 health 已可用，则不重复拉起，关闭时也不杀外部进程。
         */
        private WhisperManaged managed = new WhisperManaged();
    }

    @Data
    public static class WhisperManaged {
        /** 是否随应用启停 Whisper 子进程 */
        private boolean enabled = true;
        /**
         * whisper-service 目录（含 main.py）。
         * 相对路径相对 user.dir（一般是项目根 / 运行目录）。
         */
        private String workingDir = "./whisper-service";
        /**
         * Python 可执行文件；空则优先 workingDir/.venv，再回退系统 python。
         * Windows 示例：D:/.../whisper-service/.venv/Scripts/python.exe
         */
        private String pythonPath = "";
        /** 监听地址（uvicorn 模式） */
        private String host = "0.0.0.0";
        /** 端口；空则从 base-url 解析，默认 8000 */
        private Integer port;
        /** 覆盖 WHISPER_MODEL；空则用 whisper.model */
        private String model = "";
        private String device = "cpu";
        private String compute = "int8";
        private boolean preload = true;
        /** 启动后等待 /health 的最长时间 */
        private int startupTimeoutSeconds = 180;
        /** 关闭时等待进程退出秒数 */
        private int stopTimeoutSeconds = 15;
        /** true：启动失败则让 Spring Boot 启动失败 */
        private boolean failIfUnavailable = false;
        private String healthPath = "/health";
        /** 追加环境变量 */
        private java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
        /** 使用 uvicorn 时的额外参数 */
        private java.util.List<String> extraArgs = new java.util.ArrayList<>();
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
        /**
         * 模型连通性测试超时（秒）。超过则判定不可用。
         * 默认 10 秒，避免长时间卡住。
         */
        private int testTimeoutSeconds = 10;
    }

    @Data
    public static class Download {
        /** 最长允许视频时长（秒），0 表示不限制 */
        private int maxDurationSeconds = 7200;
        /** 提取音频格式 */
        private String audioFormat = "mp3";
        /** 下载超时（秒） */
        private int timeoutSeconds = 600;

        /**
         * 最高分辨率（高度像素）。转录场景 720 足够，比默认「最高画质」快很多。
         * 0 表示不限制（下载原画，可能很慢）。
         */
        private int maxHeight = 720;

        /**
         * 分片并发数（yt-dlp -N）。对 HLS/DASH 多连接提速明显，建议 4–16。
         */
        private int concurrentFragments = 8;

        /**
         * 自定义 yt-dlp -f 格式串；非空时优先于 maxHeight 自动拼装。
         * 例：best[height&lt;=480] / bv*[height&lt;=720]+ba/b
         */
        private String format;

        /**
         * 是否优先已封装的单文件格式（减少双轨下载+合并时间）。
         * true 时在分辨率限制内尽量选 progressive / 已合并流。
         */
        private boolean preferMerged = true;

        /**
         * 从浏览器导入 Cookie（yt-dlp --cookies-from-browser）。
         * 抖音等平台常需要：chrome / edge / firefox / brave。
         * 浏览器正在运行时 Windows 可能锁库失败，可先关浏览器或改用 cookiesFile。
         */
        private String cookiesFromBrowser;

        /**
         * Netscape 格式 cookies.txt 路径（yt-dlp --cookies）。
         * 与 cookiesFromBrowser 同时配置时优先本文件。
         */
        private String cookiesFile;

        /**
         * 额外透传给 yt-dlp 的参数（空格分隔的 token 列表不拆分；每项一个参数）。
         * 例：["--proxy", "http://127.0.0.1:7890"]
         */
        private java.util.List<String> extraArgs = new java.util.ArrayList<>();
    }

    /**
     * 多模态视频理解配置。
     * 代码默认 mode=audio_only；go-live 改为 hybrid。
     */
    @Data
    public static class Understanding {
        /** audio_only | hybrid | omni_only */
        private String mode = "audio_only";
        /** true 时走 Mock，不调云端 Omni */
        private boolean mock = false;
        private String provider = "nvidia";
        private String model = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning";
        /** auto | nvidia-omni-chat | frame-vlm | mock */
        private String protocol = "auto";
        private int timeoutSeconds = 600;
        private int writeTimeoutSeconds = 120;
        private int maxRetries = 4;
        private long retryBackoffMs = 3000;
        private int maxRequestVideoSeconds = 120;
        private int shortMaxSeconds = 90;
        private int mediumMaxSeconds = 600;
        private int chunkSeconds = 90;
        private int sampleStrideSeconds = 60;
        private int sampleWindowSeconds = 20;
        private int targetHeight = 480;
        private int targetFps = 2;
        private int maxChunksPerTask = 20;
        private long maxUploadBytes = 20_971_520L;
        private double payloadHeadroom = 0.7;
        private int reencodeMaxAttempts = 3;
        private int chunkConcurrency = 1;
        private boolean requireTranscript = true;
        private boolean fallbackToAudio = true;
        private boolean fallbackFrameVlm = true;
        private boolean cleanupChunks = true;
        private boolean enableThinking = false;
        private int thinkingTokenBudget = 1024;
        private int hybridMaxDurationSeconds = 1800;
        private int omniMaxDurationSeconds = 1800;
        /** reject | force_audio */
        private String onOmniTooLong = "reject";
        private boolean stripAudioOnVisualChunks = true;
        private int mapMaxTokens = 4096;
        private double temperature = 0.2;
        /** ASR digest：短于此字符数则零次 Map LLM */
        private int digestSingleWindowChars = 8000;
        private int digestWindowChars = 4500;
        /** FrameSample */
        private double frameIntervalSeconds = 2.0;
        private int frameMaxPerChunk = 8;
        private int frameMaxEdge = 768;
        private int frameJpegQuality = 85;
        private int frameMaxImagesPerCall = 8;
        private boolean frameIncludeAsrWindowText = true;
    }
}
