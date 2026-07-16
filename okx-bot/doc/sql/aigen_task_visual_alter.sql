-- Visual Timeline：任务表扩展字段（在已有 aigen_task 上执行）
-- 若某列已存在会报错，可跳过该行

USE okx_bot;

ALTER TABLE aigen_task ADD COLUMN pipeline_mode VARCHAR(16) DEFAULT 'template' COMMENT 'template|visual' AFTER template_id;
ALTER TABLE aigen_task ADD COLUMN audio_mode VARCHAR(16) DEFAULT NULL COMMENT 'none|bgm_only|tts' AFTER pipeline_mode;
ALTER TABLE aigen_task ADD COLUMN style_preset VARCHAR(64) DEFAULT NULL COMMENT '风格预设' AFTER audio_mode;
ALTER TABLE aigen_task ADD COLUMN shot_count INT DEFAULT NULL COMMENT '镜头数' AFTER style_preset;
ALTER TABLE aigen_task ADD COLUMN asset_done_count INT DEFAULT NULL COMMENT '已完成素材镜数' AFTER shot_count;

-- visual 出图模型（与文生图共用 ai_model_config capability=image）
ALTER TABLE aigen_task ADD COLUMN image_provider VARCHAR(64) DEFAULT NULL COMMENT '生图供应商' AFTER llm_model;
ALTER TABLE aigen_task ADD COLUMN image_model VARCHAR(128) DEFAULT NULL COMMENT '生图模型 ID' AFTER image_provider;
