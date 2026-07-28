-- 文章/新闻提取任务表
-- 可在已有库上单独执行
USE okx_bot;

CREATE TABLE IF NOT EXISTS article_task (
    id                    BIGINT         NOT NULL COMMENT '主键 snowflake',
    user_id               BIGINT         NOT NULL COMMENT '所属用户',
    source_url            VARCHAR(2048)           COMMENT '源链接，可空（纯粘贴）',
    canonical_url         VARCHAR(2048)           COMMENT '规范化/跳转后 URL',
    platform              VARCHAR(32)             COMMENT 'generic/zhihu/weibo/x/weixin/xiaohongshu/toutiao/bilibili_column/other',
    support_level         VARCHAR(16)             COMMENT 'FULL/PARTIAL/PASTE_ONLY/UNSUPPORTED',
    title                 VARCHAR(512)            COMMENT '标题',
    author                VARCHAR(256)            COMMENT '作者',
    status                VARCHAR(32)    NOT NULL DEFAULT 'PENDING',
    current_step          VARCHAR(128),
    progress              INT            NOT NULL DEFAULT 0,
    language              VARCHAR(16)    DEFAULT 'zh',

    input_mode            VARCHAR(16)    NOT NULL DEFAULT 'url' COMMENT 'url|paste|url_and_paste',
    -- 应用层限制 ≤100000 字符；MEDIUMTEXT 为存储上限
    paste_text            MEDIUMTEXT              COMMENT '用户粘贴正文，应用层 max 100000 chars',
    -- DB 仅截断副本；全文在对象存储 main_text_path
    main_text             MEDIUMTEXT              COMMENT '正文截断副本（应用层截断）；全文见 main_text_path',
    main_text_chars       INT                     COMMENT '全文字符数（截断前）',
    force_paste_only      TINYINT        DEFAULT 0 COMMENT '1=跳过 FETCH 强制用 paste',
    allow_paste_fallback  TINYINT        DEFAULT 1 COMMENT '1=抓取失败可 NEEDS_PASTE',
    paste_resume          TINYINT        DEFAULT 0 COMMENT '单轮标志：仅 /paste 入队置1；pipeline 消费后立即清0；retry 强制0',

    llm_provider          VARCHAR(64),
    llm_model             VARCHAR(128),
    extract_mind_map      TINYINT        DEFAULT 0,
    generate_rewrite      TINYINT        DEFAULT 1,
    -- 固定 JSON 数组字符串，例：["short_video_script","wechat_article"]
    rewrite_variants      VARCHAR(512)            COMMENT 'JSON array of variant ids',
    request_options_json  TEXT                    COMMENT '创建时 options 快照 JSON，便于审计',

    core_json             LONGTEXT,
    rewrite_json          LONGTEXT,
    result_json           LONGTEXT,
    raw_html_path         VARCHAR(1024),
    main_text_path        VARCHAR(1024)           COMMENT '全文对象存储 key/path',
    error_code            VARCHAR(64),
    error_message         TEXT,
    degraded              TINYINT        DEFAULT 0,
    degrade_reason        VARCHAR(512),
    quality_score         DOUBLE,

    resolve_duration_ms   BIGINT,
    fetch_duration_ms     BIGINT,
    extract_duration_ms   BIGINT,
    core_duration_ms      BIGINT,
    rewrite_duration_ms   BIGINT,
    total_duration_ms     BIGINT,

    work_dir              VARCHAR(1024),
    started_at            DATETIME(3),
    finished_at           DATETIME(3),
    created_at            DATETIME(3)    NOT NULL COMMENT '应用写入',
    updated_at            DATETIME(3)    NOT NULL COMMENT '应用写入',

    PRIMARY KEY (id),
    INDEX idx_article_user_created (user_id, created_at),
    INDEX idx_article_status (status),
    INDEX idx_article_platform (platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章/新闻提取任务';
