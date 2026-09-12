-- 知识库向量索引 outbox：笔记/附件变更入队，后台 worker 写入 Qdrant
CREATE TABLE IF NOT EXISTS kb_index_job (
    id              BIGINT        NOT NULL COMMENT '主键',
    user_id         BIGINT        NOT NULL COMMENT '所属用户',
    source_type     VARCHAR(16)   NOT NULL COMMENT 'NOTE|FILE',
    source_id       BIGINT        NOT NULL COMMENT '笔记或附件 id',
    op              VARCHAR(16)   NOT NULL COMMENT 'UPSERT|DELETE',
    status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|RUNNING|DONE|FAIL',
    attempts        INT           NOT NULL DEFAULT 0,
    next_run_at     DATETIME(3)   NOT NULL COMMENT '下次可跑时间',
    last_error      VARCHAR(512)  NULL,
    content_hash    VARCHAR(64)   NULL COMMENT '跳过未变内容',
    created_at      DATETIME(3)   NOT NULL,
    updated_at      DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_kb_index_status_run (status, next_run_at),
    INDEX idx_kb_index_source (user_id, source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库向量索引任务';
