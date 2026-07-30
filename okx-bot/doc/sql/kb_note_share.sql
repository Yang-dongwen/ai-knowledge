-- 知识库文档公开分享
USE okx_bot;

ALTER TABLE kb_note
    ADD COLUMN share_token VARCHAR(64) NULL COMMENT '公开分享令牌' AFTER deleted_at,
    ADD COLUMN share_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否开启分享 0/1' AFTER share_token,
    ADD COLUMN share_enabled_at DATETIME(3) NULL COMMENT '开启分享时间' AFTER share_enabled;

CREATE UNIQUE INDEX uk_kb_note_share_token ON kb_note (share_token);
