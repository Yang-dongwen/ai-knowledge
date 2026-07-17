package com.dwcode.okxbot.aigen.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 视频生成模块配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "aigen")
public class AigenProperties {

    private String workDir = "./data/aigen";
    private int maxConcurrentTasks = 1;

    /**
     * true：三步全部 mock（回归 Phase 0）。
     * false：按 steps.* 决定 real/mock。
     */
    private boolean mockPipeline = false;

    private long mockStepDelayMs = 800;
    private boolean cleanupOnDelete = true;

    /** Phase 1 时长上限（秒） */
    private int maxDurationSec = 90;
    private int minDurationSec = 5;

    /**
     * 默认流水线：visual | template
     */
    private String defaultPipelineMode = "visual";

    private Steps steps = new Steps();
    private Llm llm = new Llm();
    private Tts tts = new Tts();
    private Remotion remotion = new Remotion();
    private Visual visual = new Visual();

    @Data
    public static class Visual {
        private int maxShots = 12;
        private int minShots = 4;
        private int imageConcurrency = 2;
        /** false=单镜出图失败用占位图继续 */
        private boolean failOnShotError = false;
        private String defaultAudioMode = "bgm_only";
        private String defaultStylePreset = "cinematic-dark";
        /** 默认运镜：auto=按镜序自动挑选富有变化的运镜 */
        private String motionDefault = "auto";
        private String compositionId = "VisualTimeline";
        /** 预置 BGM 目录（相对运行目录或绝对路径） */
        private String bgmDir = "./data/aigen/_bgm";
        /**
         * visual 默认生图步数（任务未指定时；库表 default_steps 优先）。
         * FLUX.1-dev 建议 20～28；schnell 建议 4～8。
         */
        private int imageSteps = 28;
        private String imageProviderKey = "nvidia";
        /**
         * visual 默认生图模型（capability=image 的 model_id）。
         * 空则按库表 sort_order 取第一个启用模型。
         */
        private String defaultImageModel = "black-forest-labs/flux.1-dev";
        /** 默认是否润色出图 prompt（建议 true，提升电影感） */
        private boolean defaultEnhanceImagePrompt = true;
        /**
         * 出图语言策略：
         * <ul>
         *   <li>{@code auto} — 优先 visual.promptEn（英文），无则中文 prompt + 主题锚点</li>
         *   <li>{@code en} — 强制走英文路径（缺 promptEn 时用 LLM 临时英化或锚点英文）</li>
         *   <li>{@code zh} — 仅用中文 prompt</li>
         * </ul>
         * FLUX 等对英文实体名（Ethereum、blockchain）通常更稳。
         */
        private String imagePromptLanguage = "auto";
        /** 规划后校验镜头是否命中用户主题关键词；失败则触发 repair */
        private boolean enforceTopicKeywords = true;

        /**
         * 静图 → 动感短片。失败自动回退静图。template 模式不受影响。
         * @deprecated 见 {@link #i2vMode}；kineticClips=false 时关闭全部 i2v
         */
        private boolean kineticClips = true;
        /**
         * 图生视频模式：
         * <ul>
         *   <li>{@code kinetic} — 本地 FFmpeg zoompan（默认，稳定）</li>
         *   <li>{@code nvidia-svd} — NVIDIA Stable Video Diffusion（需开通；失败可回退）</li>
         *   <li>{@code auto} — 先试 nvidia-svd，失败再 kinetic</li>
         *   <li>{@code off} — 仅静图 + Remotion CSS 运镜</li>
         * </ul>
         */
        private String i2vMode = "kinetic";
        /** nvidia-svd 失败时是否回退 kinetic */
        private boolean i2vFailOpenToKinetic = true;
        private String svdInvokeUrl =
                "https://ai.api.nvidia.com/v1/genai/stabilityai/stable-video-diffusion";
        private int svdTimeoutSeconds = 300;
        private int svdMotionBucketId = 127;
        private int svdFps = 6;
        /** 动效片段帧率（kinetic） */
        private int kineticFps = 24;
        /** libx264 preset：ultrafast|veryfast|faster|fast|medium */
        private String kineticPreset = "veryfast";
        private int kineticCrf = 20;
    }

    @Data
    public static class Steps {
        /** real | mock */
        private String plan = "real";
        /** real | mock — real 时走 TTS 生成真实音频 */
        private String asset = "real";
        /** real | mock */
        private String render = "real";
    }

    @Data
    public static class Llm {
        private String provider;
        private String model;
        /** 导演创意温度：略高以鼓励自由想象 */
        private double temperature = 0.72;
        private int maxTokens = 8192;
        private int maxRetries = 3;
        private int timeoutSeconds = 120;
        private String structuredMode = "auto";
        private int maxRepairAttempts = 1;
        private boolean logPrompts = false;
    }

    @Data
    public static class Tts {
        /**
         * auto | edge | windows | mock
         * auto：优先 edge-tts，否则 Windows SAPI，再否则按 fail-open-to-mock
         */
        private String provider = "auto";
        /** Edge 默认音色 */
        private String defaultVoice = "zh-CN-XiaoxiaoNeural";
        private int timeoutSeconds = 120;
        /** edge-tts CLI 命令名或绝对路径 */
        private String edgeCommand = "edge-tts";
        /** auto | cli | python */
        private String edgeMode = "auto";
        /** python 可执行文件（edgeMode=python 或探测用） */
        private String pythonPath = "python";
        /** 无 TTS 时是否降级 mock（生产建议 false） */
        private boolean failOpenToMock = false;
        /** 可选；空则尝试从 video.ffmpeg-path 推导 ffprobe */
        private String ffprobePath = "";
        private String ffmpegPath = "";
        /** 音频尾部留白帧（避免截断） */
        private int tailPaddingFrames = 6;
    }

    @Data
    public static class Remotion {
        private String baseUrl = "http://127.0.0.1:3100";
        private int timeoutSeconds = 600;
        private String codec = "h264";
        private int crf = 18;
        /** 可选：与 Node 服务共享的校验 token */
        private String renderToken = "";

        /**
         * 是否由 Java 托管 aigen-remotion 子进程。
         * true：启动/渲染时自动拉起，关闭 Spring 时停止（仅本进程拉起的）。
         * false：需手动 npm run render-server。
         */
        private boolean manageProcess = true;

        /**
         * true：Spring Boot 就绪后立即拉起；
         * false：首次真实渲染时再拉起（更省资源）。
         */
        private boolean autoStartOnBoot = true;

        /**
         * aigen-remotion 工程目录（相对运行目录或绝对路径）。
         * 默认兼容：仓库根/aigen-remotion 或 ../aigen-remotion。
         */
        private String projectDir = "../aigen-remotion";

        /** node 可执行文件，默认 node（需在 PATH 中） */
        private String nodePath = "node";

        /** 等待 /health 就绪的超时（秒）。首次 webpack bundle 可能较久 */
        private int startupTimeoutSeconds = 180;
    }
}

