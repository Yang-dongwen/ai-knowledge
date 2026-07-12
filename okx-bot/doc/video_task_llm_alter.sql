-- 视频任务增加 LLM 模型字段
USE okx_bot;

ALTER TABLE video_task ADD COLUMN llm_provider VARCHAR(64) COMMENT 'LLM供应商标识' AFTER language;
ALTER TABLE video_task ADD COLUMN llm_model VARCHAR(128) COMMENT 'LLM模型ID' AFTER llm_provider;
