-- 知识库：附件表 + 笔记正文格式（富文本）
USE okx_bot;

ALTER TABLE kb_note
    ADD COLUMN content_format VARCHAR(16) NOT NULL DEFAULT 'markdown'
        COMMENT 'html|markdown' AFTER content;

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
