-- 视频任务增加各步骤耗时字段
USE okx_bot;

ALTER TABLE video_task ADD COLUMN download_duration_ms BIGINT COMMENT '下载步骤耗时(毫秒)' AFTER error_message;
ALTER TABLE video_task ADD COLUMN transcribe_duration_ms BIGINT COMMENT '转录步骤耗时(毫秒)' AFTER download_duration_ms;
ALTER TABLE video_task ADD COLUMN summarize_duration_ms BIGINT COMMENT '总结步骤耗时(毫秒)' AFTER transcribe_duration_ms;
ALTER TABLE video_task ADD COLUMN total_duration_ms BIGINT COMMENT '全流程总耗时(毫秒)' AFTER summarize_duration_ms;
