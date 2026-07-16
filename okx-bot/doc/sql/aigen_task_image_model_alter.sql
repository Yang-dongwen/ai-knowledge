-- aigen visual 出图模型字段（若已执行 aigen_task_visual_alter 最新版可跳过）
USE okx_bot;

ALTER TABLE aigen_task ADD COLUMN image_provider VARCHAR(64) DEFAULT NULL COMMENT '生图供应商' AFTER llm_model;
ALTER TABLE aigen_task ADD COLUMN image_model VARCHAR(128) DEFAULT NULL COMMENT '生图模型 ID' AFTER image_provider;
