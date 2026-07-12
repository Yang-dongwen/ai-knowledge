-- 将已有 video_task 表升级到 v2 持久化字段
-- 若某列已存在会报 Duplicate column，跳过该行即可
USE okx_bot;

ALTER TABLE video_task ADD COLUMN platform VARCHAR(32) COMMENT '平台 douyin/bilibili/youtube/xiaohongshu/other' AFTER title;
ALTER TABLE video_task ADD COLUMN transcription_path VARCHAR(1024) COMMENT '转录JSON文件路径' AFTER audio_path;
ALTER TABLE video_task ADD COLUMN summary_path VARCHAR(1024) COMMENT '摘要JSON文件路径' AFTER transcription_path;
ALTER TABLE video_task ADD COLUMN summary_json LONGTEXT COMMENT 'AI核心内容JSON' AFTER transcription_json;
ALTER TABLE video_task ADD INDEX idx_platform (platform);
