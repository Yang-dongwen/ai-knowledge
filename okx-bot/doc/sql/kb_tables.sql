-- 个人知识库表（Phase 1）
-- 数据库：okx_bot
USE okx_bot;

CREATE TABLE IF NOT EXISTS kb_category (
    id          BIGINT       NOT NULL COMMENT '主键',
    user_id     BIGINT       NOT NULL COMMENT '所属用户',
    name        VARCHAR(64)  NOT NULL COMMENT '分类名',
    parent_id   BIGINT       NULL COMMENT '父分类，NULL 为根',
    sort_order  INT          NOT NULL DEFAULT 0 COMMENT '同级排序，小在前',
    created_at  DATETIME(3)  NOT NULL COMMENT '创建时间',
    updated_at  DATETIME(3)  NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_kb_cat_user_parent (user_id, parent_id),
    INDEX idx_kb_cat_user_sort (user_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库分类';

CREATE TABLE IF NOT EXISTS kb_tag (
    id          BIGINT       NOT NULL COMMENT '主键',
    user_id     BIGINT       NOT NULL COMMENT '所属用户',
    name        VARCHAR(64)  NOT NULL COMMENT '标签名',
    created_at  DATETIME(3)  NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_tag_user_name (user_id, name),
    INDEX idx_kb_tag_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库标签';

CREATE TABLE IF NOT EXISTS kb_note (
    id             BIGINT        NOT NULL COMMENT '主键',
    user_id        BIGINT        NOT NULL COMMENT '所属用户',
    title          VARCHAR(200)  NOT NULL DEFAULT '' COMMENT '标题',
    content        LONGTEXT      NULL COMMENT '正文（markdown 或 html）',
    content_format VARCHAR(16)   NOT NULL DEFAULT 'markdown' COMMENT 'html|markdown',
    snippet        VARCHAR(512)  NULL COMMENT '列表摘要（纯文本）',
    category_id    BIGINT        NULL COMMENT '分类，可空',
    is_pinned      TINYINT       NOT NULL DEFAULT 0 COMMENT '置顶 0/1',
    is_deleted     TINYINT       NOT NULL DEFAULT 0 COMMENT '软删 0/1',
    deleted_at     DATETIME(3)   NULL COMMENT '软删时间',
    share_token    VARCHAR(64)   NULL COMMENT '公开分享令牌',
    share_enabled  TINYINT       NOT NULL DEFAULT 0 COMMENT '是否开启分享 0/1',
    share_enabled_at DATETIME(3) NULL COMMENT '开启分享时间',
    created_at     DATETIME(3)   NOT NULL COMMENT '创建时间',
    updated_at     DATETIME(3)   NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_note_share_token (share_token),
    INDEX idx_kb_note_user_updated (user_id, is_deleted, updated_at),
    INDEX idx_kb_note_user_cat (user_id, category_id, is_deleted),
    INDEX idx_kb_note_user_pinned (user_id, is_pinned, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库笔记';

-- 中文全文检索（需 ngram；若环境不支持可跳过，应用层用 LIKE）
-- ALTER TABLE kb_note ADD FULLTEXT INDEX ft_kb_note_title_content (title, content) WITH PARSER ngram;

CREATE TABLE IF NOT EXISTS kb_note_tag (
    note_id BIGINT NOT NULL COMMENT '笔记ID',
    tag_id  BIGINT NOT NULL COMMENT '标签ID',
    PRIMARY KEY (note_id, tag_id),
    INDEX idx_kb_note_tag_tag (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='笔记标签关联';

CREATE TABLE IF NOT EXISTS kb_file (
    id             BIGINT        NOT NULL COMMENT '主键',
    user_id        BIGINT        NOT NULL COMMENT '所属用户',
    note_id        BIGINT        NULL COMMENT '关联笔记，可空',
    original_name  VARCHAR(255)  NOT NULL COMMENT '原始文件名',
    content_type   VARCHAR(128)  NULL COMMENT 'MIME',
    size_bytes     BIGINT        NOT NULL DEFAULT 0 COMMENT '字节数',
    object_key     VARCHAR(512)  NOT NULL COMMENT '对象存储 key',
    kind           VARCHAR(32)   NOT NULL DEFAULT 'other' COMMENT 'image|video|audio|pdf|office|other',
    created_at     DATETIME(3)   NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_kb_file_user_note (user_id, note_id),
    INDEX idx_kb_file_user_created (user_id, created_at),
    UNIQUE KEY uk_kb_file_object_key (object_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库附件';
