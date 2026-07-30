-- 列表性能：预计算摘要，避免 list 接口加载整篇 LONGTEXT
USE okx_bot;

ALTER TABLE kb_note
    ADD COLUMN snippet VARCHAR(512) NULL COMMENT '列表摘要（纯文本）' AFTER content_format;

-- 可选：已有数据回填（仅截取前 400 字符，HTML 标签可能残留，业务层再 strip）
UPDATE kb_note
SET snippet = LEFT(REGEXP_REPLACE(IFNULL(content, ''), '<[^>]+>', ' '), 400)
WHERE snippet IS NULL AND content IS NOT NULL
  AND content != '';
