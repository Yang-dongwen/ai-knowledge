-- 模型配置支持 capability：chat（对话/润色/分镜）| image（文生图）
-- 以及文生图专用字段
USE okx_bot;

ALTER TABLE ai_model_config
    ADD COLUMN capability    VARCHAR(32)  NOT NULL DEFAULT 'chat'
        COMMENT '能力：chat | image' AFTER model_name,
    ADD COLUMN invoke_url    VARCHAR(512) NULL
        COMMENT '生图 GenAI 完整 URL（capability=image 时必填）' AFTER capability,
    ADD COLUMN default_steps INT          NULL
        COMMENT '生图默认步数' AFTER invoke_url,
    ADD COLUMN max_steps     INT          NULL
        COMMENT '生图最大步数' AFTER default_steps;

-- 唯一约束改为 provider + model_id + capability（允许同 ID 不同能力，极少见）
-- 若旧唯一键名不同，请按实际库调整
ALTER TABLE ai_model_config DROP INDEX uk_provider_model;
ALTER TABLE ai_model_config
    ADD UNIQUE KEY uk_provider_model_cap (provider, model_id, capability);

-- 可选：预置两个 NVIDIA FLUX 生图模型（无则插入；api-key 仍用 yml）
INSERT INTO ai_model_config (
    id, provider, model_id, model_name, capability, invoke_url,
    default_steps, max_steps, enabled, sort_order, remark, created_at, updated_at
)
SELECT
    3000000000000000002, 'nvidia', 'black-forest-labs/flux.1-dev', 'FLUX.1-dev（高质量）',
    'image', 'https://ai.api.nvidia.com/v1/genai/black-forest-labs/flux.1-dev',
    28, 50, 1, 5, '画质优先，默认推荐', NOW(3), NOW(3)
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM ai_model_config
    WHERE provider = 'nvidia' AND model_id = 'black-forest-labs/flux.1-dev' AND capability = 'image'
);

INSERT INTO ai_model_config (
    id, provider, model_id, model_name, capability, invoke_url,
    default_steps, max_steps, enabled, sort_order, remark, created_at, updated_at
)
SELECT
    3000000000000000001, 'nvidia', 'black-forest-labs/flux.1-schnell', 'FLUX.1-schnell（快速）',
    'image', 'https://ai.api.nvidia.com/v1/genai/black-forest-labs/flux.1-schnell',
    4, 8, 1, 20, '蒸馏快速模型，适合预览', NOW(3), NOW(3)
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM ai_model_config
    WHERE provider = 'nvidia' AND model_id = 'black-forest-labs/flux.1-schnell' AND capability = 'image'
);
