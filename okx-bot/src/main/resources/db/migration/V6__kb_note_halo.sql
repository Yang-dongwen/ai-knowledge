-- 知识库笔记与 Halo 文章弱引用
ALTER TABLE kb_note
    ADD COLUMN halo_post_name VARCHAR(64) NULL COMMENT 'Halo metadata.name' AFTER share_enabled_at,
    ADD COLUMN halo_permalink VARCHAR(512) NULL COMMENT '对外文章 URL' AFTER halo_post_name,
    ADD COLUMN halo_published_at DATETIME(3) NULL COMMENT '最近一次发到博客' AFTER halo_permalink;
