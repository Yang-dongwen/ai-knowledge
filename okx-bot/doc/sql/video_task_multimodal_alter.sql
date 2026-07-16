-- 视频任务多模态理解字段（VideoCoreExtractor Hybrid）
-- 若某列已存在会报 Duplicate column，跳过该行即可
USE okx_bot;

ALTER TABLE video_task ADD COLUMN understanding_mode VARCHAR(32) DEFAULT 'audio_only' COMMENT 'audio_only/hybrid/omni_only' AFTER language;
ALTER TABLE video_task ADD COLUMN omni_provider VARCHAR(64) COMMENT '多模态供应商' AFTER llm_model;
ALTER TABLE video_task ADD COLUMN omni_model VARCHAR(128) COMMENT '多模态模型ID' AFTER omni_provider;
ALTER TABLE video_task ADD COLUMN visual_json LONGTEXT COMMENT '视觉理解JSON' AFTER summary_json;
ALTER TABLE video_task ADD COLUMN visual_path VARCHAR(1024) COMMENT '视觉理解JSON路径' AFTER summary_path;
ALTER TABLE video_task ADD COLUMN understand_duration_ms BIGINT COMMENT '画面理解耗时ms' AFTER transcribe_duration_ms;
ALTER TABLE video_task ADD COLUMN degraded TINYINT DEFAULT 0 COMMENT '是否降级成功 1是' AFTER error_message;
ALTER TABLE video_task ADD COLUMN degrade_reason VARCHAR(512) COMMENT '降级原因' AFTER degraded;

-- 可选：注册默认 Omni 模型（enabled=0，go-live 前手工启用）
INSERT INTO ai_model_config (id, provider, model_id, model_name, capability, protocol, enabled, sort_order, remark, created_at, updated_at)
SELECT
  9000000000000000001,
  'nvidia',
  'nvidia/nemotron-3-nano-omni-30b-a3b-reasoning',
  'Nemotron 3 Nano Omni (Reasoning)',
  'video_omni',
  'nvidia-omni-chat',
  0,
  10,
  '多模态视频理解；默认禁用，验证后启用',
  NOW(3),
  NOW(3)
WHERE NOT EXISTS (
  SELECT 1 FROM ai_model_config
  WHERE provider = 'nvidia'
    AND model_id = 'nvidia/nemotron-3-nano-omni-30b-a3b-reasoning'
    AND capability = 'video_omni'
);
