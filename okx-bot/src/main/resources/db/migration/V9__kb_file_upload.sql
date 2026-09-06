-- 知识库附件分片上传会话（1GB 上限；分片落 scratch，complete 后写入 kb_file）
CREATE TABLE IF NOT EXISTS kb_file_upload (
    id              BIGINT        NOT NULL COMMENT '主键',
    user_id         BIGINT        NOT NULL COMMENT '所属用户',
    note_id         BIGINT        NULL COMMENT '关联笔记，可空',
    original_name   VARCHAR(255)  NOT NULL COMMENT '原始文件名',
    content_type    VARCHAR(128)  NULL COMMENT 'MIME',
    kind            VARCHAR(32)   NOT NULL DEFAULT 'other' COMMENT 'image|video|audio|pdf|office|other',
    total_bytes     BIGINT        NOT NULL COMMENT '声明总大小',
    chunk_size      INT           NOT NULL COMMENT '分片字节数',
    total_parts     INT           NOT NULL COMMENT '分片数',
    status          VARCHAR(16)   NOT NULL DEFAULT 'pending' COMMENT 'pending|completing|completed|aborted',
    file_id         BIGINT        NULL COMMENT '完成后的 kb_file.id',
    created_at      DATETIME(3)   NOT NULL COMMENT '创建时间',
    updated_at      DATETIME(3)   NOT NULL COMMENT '更新时间',
    expires_at      DATETIME(3)   NOT NULL COMMENT '过期时间',
    PRIMARY KEY (id),
    INDEX idx_kb_upload_user_status (user_id, status),
    INDEX idx_kb_upload_expires (expires_at, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库附件分片上传会话';
