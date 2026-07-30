-- 知识库文档同级排序（F2 目录拖拽/排序）
USE okx_bot;

ALTER TABLE kb_note
    ADD COLUMN sort_order INT NOT NULL DEFAULT 0 COMMENT '同文件夹内排序，小在前' AFTER category_id;

ALTER TABLE kb_note
    ADD INDEX idx_kb_note_user_cat_sort (user_id, category_id, is_deleted, sort_order);
