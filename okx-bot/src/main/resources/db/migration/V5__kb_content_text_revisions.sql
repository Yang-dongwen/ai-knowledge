-- 知识库增强：纯文本检索副本 + 版本历史
-- content_text：保存时从 html/md 剥离，供检索/高亮（避免对 content LONGTEXT 直接 LIKE）

ALTER TABLE kb_note
    ADD COLUMN content_text MEDIUMTEXT NULL COMMENT '纯文本检索副本' AFTER snippet;

CREATE TABLE IF NOT EXISTS kb_note_revision (
    id             BIGINT        NOT NULL COMMENT '主键',
    note_id        BIGINT        NOT NULL COMMENT '笔记ID',
    user_id        BIGINT        NOT NULL COMMENT '所属用户',
    title          VARCHAR(200)  NOT NULL DEFAULT '' COMMENT '标题快照',
    content        LONGTEXT      NULL COMMENT '正文快照',
    content_format VARCHAR(16)   NOT NULL DEFAULT 'html' COMMENT 'html|markdown',
    source         VARCHAR(32)   NOT NULL DEFAULT 'save' COMMENT 'save|restore|manual',
    created_at     DATETIME(3)   NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_kb_rev_note_created (note_id, created_at),
    INDEX idx_kb_rev_user_note (user_id, note_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库笔记版本';
