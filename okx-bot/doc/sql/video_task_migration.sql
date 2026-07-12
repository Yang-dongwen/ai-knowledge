-- 视频核心内容提取模块：建表 + v2 持久化字段增量
-- 可在已有库上单独执行
USE okx_bot;

CREATE TABLE IF NOT EXISTS video_task (
    id BIGINT NOT NULL COMMENT '主键',
    source_url VARCHAR(1024) NOT NULL COMMENT '源视频URL',
    title VARCHAR(512) COMMENT '视频标题',
    platform VARCHAR(32) COMMENT '平台 douyin/bilibili/youtube/xiaohongshu/other',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态 PENDING/DOWNLOADING/TRANSCRIBING/SUMMARIZING/SUCCESS/FAILED',
    current_step VARCHAR(128) COMMENT '当前步骤说明',
    language VARCHAR(16) DEFAULT 'zh' COMMENT '语言',
    extract_mind_map TINYINT NOT NULL DEFAULT 1 COMMENT '是否提取思维导图 1是 0否',
    generate_repurpose_script TINYINT NOT NULL DEFAULT 1 COMMENT '是否生成repurpose脚本 1是 0否',
    duration_seconds DOUBLE COMMENT '视频时长(秒)',
    video_path VARCHAR(1024) COMMENT '本地视频路径',
    audio_path VARCHAR(1024) COMMENT '本地音频路径',
    transcription_path VARCHAR(1024) COMMENT '转录JSON文件路径',
    summary_path VARCHAR(1024) COMMENT '摘要JSON文件路径',
    transcription_json LONGTEXT COMMENT '转录结果JSON(带时间戳)',
    summary_json LONGTEXT COMMENT 'AI核心内容JSON',
    result_json LONGTEXT COMMENT '完整结构化结果JSON',
    error_message TEXT COMMENT '错误信息',
    started_at DATETIME(3) COMMENT '开始处理时间',
    finished_at DATETIME(3) COMMENT '完成时间',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_status (status),
    INDEX idx_platform (platform),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频处理任务表';

-- 已有表升级到 v2（列已存在时会报错，可按需注释执行）
-- ALTER TABLE video_task ADD COLUMN platform VARCHAR(32) COMMENT '平台' AFTER title;
-- ALTER TABLE video_task ADD COLUMN transcription_path VARCHAR(1024) COMMENT '转录JSON文件路径' AFTER audio_path;
-- ALTER TABLE video_task ADD COLUMN summary_path VARCHAR(1024) COMMENT '摘要JSON文件路径' AFTER transcription_path;
-- ALTER TABLE video_task ADD COLUMN summary_json LONGTEXT COMMENT 'AI核心内容JSON' AFTER transcription_json;
-- ALTER TABLE video_task ADD INDEX idx_platform (platform);
