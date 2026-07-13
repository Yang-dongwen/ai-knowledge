-- AI 文生图任务表
-- 可在已有库上单独执行
USE okx_bot;

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
