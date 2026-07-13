-- AI 视频生成 Phase 0：任务表
-- 可在已有库上单独执行
USE okx_bot;

CREATE TABLE IF NOT EXISTS aigen_task (
    id                   BIGINT        NOT NULL COMMENT '主键 snowflake',
    user_id              BIGINT        NOT NULL COMMENT '所属用户',
    title                VARCHAR(256)           COMMENT '标题',
    prompt               TEXT          NOT NULL COMMENT '用户原始提示词',
    negative_prompt      VARCHAR(1024)          COMMENT '负向约束（可选）',
    template_id          VARCHAR(64)   NOT NULL COMMENT '模板 ID',
    status               VARCHAR(32)   NOT NULL DEFAULT 'PENDING' COMMENT '状态',
    current_step         VARCHAR(128)           COMMENT '当前步骤说明',
    progress             INT           NOT NULL DEFAULT 0 COMMENT '0-100',

    language             VARCHAR(16)   DEFAULT 'zh',
    aspect_ratio         VARCHAR(16)   DEFAULT '9:16' COMMENT '9:16 / 16:9 / 1:1',
    target_duration_sec  INT           DEFAULT 30,
    style_json           JSON                   COMMENT '主题色/字体等覆盖',
    voice_id             VARCHAR(64)            COMMENT 'TTS 音色',
    bgm_id               VARCHAR(64)            COMMENT '背景音乐',

    llm_provider         VARCHAR(64),
    llm_model            VARCHAR(128),

    storyboard_json      LONGTEXT               COMMENT '完整分镜契约',
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
