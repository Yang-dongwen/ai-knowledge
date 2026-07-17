-- AI 工具台 数据库建表脚本
-- 数据库：okx_bot（仅认证 + AI 工具，已移除交易/回测表）

CREATE DATABASE IF NOT EXISTS okx_bot DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE okx_bot;

-- 9. AI 对话会话表（按用户隔离）
CREATE TABLE IF NOT EXISTS chat_conversation (
    id BIGINT NOT NULL COMMENT '主键',
    user_id BIGINT NOT NULL COMMENT '所属用户ID',
    title VARCHAR(255) NOT NULL DEFAULT '新对话' COMMENT '会话标题',
    provider VARCHAR(64) COMMENT '供应商标识',
    model VARCHAR(128) COMMENT '模型ID',
    temperature DOUBLE NULL COMMENT '会话温度 0~2，空则默认 0.7',
    max_tokens INT NULL COMMENT '会话 max_tokens，空则默认 2000',
    system_prompt TEXT NULL COMMENT '会话自定义系统提示，空则用全局默认',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_updated_at (updated_at),
    INDEX idx_chat_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI对话会话表';

-- 10. AI 对话消息表
CREATE TABLE IF NOT EXISTS chat_message (
    id BIGINT NOT NULL COMMENT '主键',
    conversation_id BIGINT NOT NULL COMMENT '会话ID',
    role VARCHAR(16) NOT NULL COMMENT '角色 user/assistant',
    content TEXT NOT NULL COMMENT '消息内容',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI对话消息表';

-- ============================================================

-- 12. 回测交易明细表
-- 13. 回测资金曲线表
-- ============================================================
-- 视频核心内容提取模块（VideoCoreExtractor）
-- 流程：下载(yt-dlp) → 音频提取(FFmpeg) → 转录(Whisper) → 总结(LLM)
-- ============================================================

-- 14. 视频处理任务表（v2 持久化：视频/转录/核心内容）
CREATE TABLE IF NOT EXISTS video_task (
    id BIGINT NOT NULL COMMENT '主键',
    user_id BIGINT COMMENT '所属用户ID',
    source_url VARCHAR(1024) NOT NULL COMMENT '源视频URL',
    title VARCHAR(512) COMMENT '视频标题',
    platform VARCHAR(32) COMMENT '平台 douyin/bilibili/youtube/xiaohongshu/other',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态含 UNDERSTANDING',
    current_step VARCHAR(128) COMMENT '当前步骤说明',
    language VARCHAR(16) DEFAULT 'zh' COMMENT '语言',
    understanding_mode VARCHAR(32) DEFAULT 'audio_only' COMMENT 'audio_only/hybrid/omni_only',
    llm_provider VARCHAR(64) COMMENT 'LLM供应商标识',
    llm_model VARCHAR(128) COMMENT 'LLM模型ID',
    omni_provider VARCHAR(64) COMMENT '多模态供应商',
    omni_model VARCHAR(128) COMMENT '多模态模型ID',
    extract_mind_map TINYINT NOT NULL DEFAULT 1 COMMENT '是否提取思维导图 1是 0否',
    generate_repurpose_script TINYINT NOT NULL DEFAULT 1 COMMENT '是否生成repurpose脚本 1是 0否',
    duration_seconds DOUBLE COMMENT '视频时长(秒)',
    video_path VARCHAR(1024) COMMENT '本地视频路径',
    audio_path VARCHAR(1024) COMMENT '本地音频路径',
    transcription_path VARCHAR(1024) COMMENT '转录JSON文件路径',
    summary_path VARCHAR(1024) COMMENT '摘要JSON文件路径',
    visual_path VARCHAR(1024) COMMENT '视觉理解JSON路径',
    transcription_json LONGTEXT COMMENT '转录结果JSON(带时间戳)',
    summary_json LONGTEXT COMMENT 'AI核心内容JSON',
    visual_json LONGTEXT COMMENT '视觉理解JSON',
    result_json LONGTEXT COMMENT '完整结构化结果JSON',
    error_message TEXT COMMENT '错误信息',
    degraded TINYINT DEFAULT 0 COMMENT '是否降级成功',
    degrade_reason VARCHAR(512) COMMENT '降级原因',
    download_duration_ms BIGINT COMMENT '下载步骤耗时(毫秒)',
    transcribe_duration_ms BIGINT COMMENT '转录步骤耗时(毫秒)',
    understand_duration_ms BIGINT COMMENT '画面理解耗时(毫秒)',
    summarize_duration_ms BIGINT COMMENT '总结步骤耗时(毫秒)',
    total_duration_ms BIGINT COMMENT '全流程总耗时(毫秒)',
    started_at DATETIME(3) COMMENT '开始处理时间',
    finished_at DATETIME(3) COMMENT '完成时间',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_status (status),
    INDEX idx_platform (platform),
    INDEX idx_user_id (user_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频处理任务表';

-- 15. 系统用户表（邮箱即登录名）
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT NOT NULL COMMENT '主键',
    email VARCHAR(128) NOT NULL COMMENT '邮箱/用户名',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt密码哈希',
    nickname VARCHAR(64) COMMENT '昵称',
    role VARCHAR(32) NOT NULL DEFAULT 'USER' COMMENT 'USER普通/MEMBER会员/SUPER_ADMIN超管',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
    email_verified TINYINT NOT NULL DEFAULT 0 COMMENT '1已验证',
    last_login_at DATETIME(3) COMMENT '最后登录时间',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_email (email),
    INDEX idx_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- 16. 邮箱验证码表
CREATE TABLE IF NOT EXISTS email_code (
    id BIGINT NOT NULL COMMENT '主键',
    email VARCHAR(128) NOT NULL COMMENT '邮箱',
    code VARCHAR(16) NOT NULL COMMENT '验证码',
    purpose VARCHAR(32) NOT NULL COMMENT 'REGISTER/RESET_PASSWORD',
    expires_at DATETIME(3) NOT NULL COMMENT '过期时间',
    used TINYINT NOT NULL DEFAULT 0 COMMENT '0未使用 1已使用',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_email_purpose (email, purpose),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邮箱验证码表';

-- 17. 可配置 AI 模型表（chat 对话 + image 文生图，替代 yml models 列表）
CREATE TABLE IF NOT EXISTS ai_model_config (
    id BIGINT NOT NULL COMMENT '主键',
    provider VARCHAR(64) NOT NULL COMMENT '供应商标识，对应 ai.providers 的 key',
    model_id VARCHAR(128) NOT NULL COMMENT 'API模型ID',
    model_name VARCHAR(128) NOT NULL COMMENT '展示名称',
    capability VARCHAR(32) NOT NULL DEFAULT 'chat' COMMENT '能力 chat | image | video_omni',
    invoke_url VARCHAR(512) NULL COMMENT '生图 GenAI URL（image 用）',
    default_steps INT NULL COMMENT '生图默认步数',
    max_steps INT NULL COMMENT '生图最大步数',
    protocol VARCHAR(64) NULL COMMENT '生图协议，当前仅 nvidia-flux',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用 1是 0否',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序，越小越靠前',
    remark VARCHAR(255) COMMENT '备注',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_provider_model_cap (provider, model_id, capability),
    INDEX idx_cap_enabled_sort (capability, enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI模型配置表（chat/image）';


-- 18. AI 视频生成任务表（Phase 0）
CREATE TABLE IF NOT EXISTS aigen_task (
    id                   BIGINT        NOT NULL COMMENT '主键 snowflake',
    user_id              BIGINT        NOT NULL COMMENT '所属用户',
    title                VARCHAR(256)           COMMENT '标题',
    prompt               TEXT          NOT NULL COMMENT '用户原始提示词',
    negative_prompt      VARCHAR(1024)          COMMENT '负向约束',
    template_id          VARCHAR(64)   NOT NULL COMMENT '模板 ID',
    pipeline_mode        VARCHAR(16)   DEFAULT 'template' COMMENT 'template|visual',
    audio_mode           VARCHAR(16)            COMMENT 'none|bgm_only|tts',
    style_preset         VARCHAR(64)            COMMENT '风格预设',
    shot_count           INT                    COMMENT '镜头数',
    asset_done_count     INT                    COMMENT '已完成素材镜数',
    status               VARCHAR(32)   NOT NULL DEFAULT 'PENDING' COMMENT '状态',
    current_step         VARCHAR(128)           COMMENT '当前步骤说明',
    progress             INT           NOT NULL DEFAULT 0 COMMENT '0-100',
    language             VARCHAR(16)   DEFAULT 'zh',
    aspect_ratio         VARCHAR(16)   DEFAULT '9:16',
    target_duration_sec  INT           DEFAULT 30,
    style_json           JSON                   COMMENT '主题色/字体等',
    voice_id             VARCHAR(64),
    bgm_id               VARCHAR(64),
    llm_provider         VARCHAR(64),
    llm_model            VARCHAR(128),
    image_provider       VARCHAR(64)            COMMENT '生图供应商',
    image_model          VARCHAR(128)           COMMENT '生图模型 ID',
    storyboard_json      LONGTEXT,
    storyboard_path      VARCHAR(1024),
    work_dir             VARCHAR(1024),
    output_path          VARCHAR(1024),
    poster_path          VARCHAR(1024),
    output_size_bytes    BIGINT,
    duration_seconds     DOUBLE,
    error_message        TEXT,
    plan_duration_ms     BIGINT,
    asset_duration_ms    BIGINT,
    render_duration_ms   BIGINT,
    total_duration_ms    BIGINT,
    started_at           DATETIME(3),
    finished_at          DATETIME(3),
    created_at           DATETIME(3)   NOT NULL,
    updated_at           DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_aigen_user_created (user_id, created_at),
    INDEX idx_aigen_status (status),
    INDEX idx_aigen_template (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI视频生成任务';


-- 19. AI 文生图任务表
CREATE TABLE IF NOT EXISTS imggen_task (
    id                    BIGINT         NOT NULL COMMENT '主键 snowflake',
    user_id               BIGINT         NOT NULL COMMENT '所属用户',
    title                 VARCHAR(256)            COMMENT '标题',
    prompt                TEXT           NOT NULL COMMENT '用户原始提示词',
    enhanced_prompt       TEXT                    COMMENT '润色后提示词',
    negative_prompt       VARCHAR(1024)           COMMENT '负向提示词',
    status                VARCHAR(32)    NOT NULL DEFAULT 'PENDING' COMMENT '状态',
    current_step          VARCHAR(128)            COMMENT '当前步骤说明',
    progress              INT            NOT NULL DEFAULT 0 COMMENT '0-100',
    provider              VARCHAR(64)             COMMENT '生图供应商 key',
    model                 VARCHAR(128)            COMMENT '生图模型路径',
    aspect_ratio          VARCHAR(16)             COMMENT '1:1 / 16:9 / 9:16',
    width                 INT                     COMMENT '像素宽',
    height                INT                     COMMENT '像素高',
    steps                 INT                     COMMENT '扩散步数',
    n                     INT            DEFAULT 1 COMMENT '张数',
    seed                  BIGINT         NULL     COMMENT '随机种子',
    enhance_enabled       TINYINT        DEFAULT 0 COMMENT '是否启用 prompt 润色',
    llm_provider          VARCHAR(64)             COMMENT '润色用 LLM 供应商',
    llm_model             VARCHAR(128)            COMMENT '润色用 LLM 模型',
    result_json           LONGTEXT                COMMENT '多图元数据 JSON',
    work_dir              VARCHAR(1024),
    cover_path            VARCHAR(1024)           COMMENT '封面/首图绝对路径',
    error_message         TEXT,
    provider_request_id   VARCHAR(128),
    enhance_duration_ms   BIGINT,
    generate_duration_ms  BIGINT,
    total_duration_ms     BIGINT,
    started_at            DATETIME(3),
    finished_at           DATETIME(3),
    created_at            DATETIME(3)    NOT NULL,
    updated_at            DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_imggen_user_created (user_id, created_at),
    INDEX idx_imggen_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI文生图任务';
