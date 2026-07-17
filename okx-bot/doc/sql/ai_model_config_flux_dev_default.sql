-- 将 FLUX.1-dev 设为优先生图模型，并确保 schnell 仍可选用
-- 在已有 ai_model_config 表上执行（可重复执行）
USE okx_bot;

-- 1) 高质量默认：FLUX.1-dev
INSERT INTO ai_model_config (
    id, provider, model_id, model_name, capability, invoke_url,
    default_steps, max_steps, protocol, enabled, sort_order, remark, created_at, updated_at
)
SELECT
    3100000000000000001,
    'nvidia',
    'black-forest-labs/flux.1-dev',
    'FLUX.1-dev（高质量）',
    'image',
    'https://ai.api.nvidia.com/v1/genai/black-forest-labs/flux.1-dev',
    28,
    50,
    'nvidia-flux',
    1,
    5,
    '画质优先，较慢；视频生成 visual 默认推荐',
    NOW(3),
    NOW(3)
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM ai_model_config
    WHERE provider = 'nvidia'
      AND model_id = 'black-forest-labs/flux.1-dev'
      AND capability = 'image'
);

UPDATE ai_model_config
SET model_name = 'FLUX.1-dev（高质量）',
    invoke_url = 'https://ai.api.nvidia.com/v1/genai/black-forest-labs/flux.1-dev',
    default_steps = 28,
    max_steps = 50,
    protocol = COALESCE(NULLIF(protocol, ''), 'nvidia-flux'),
    enabled = 1,
    sort_order = 5,
    remark = '画质优先，较慢；视频生成 visual 默认推荐',
    updated_at = NOW(3)
WHERE provider = 'nvidia'
  AND model_id = 'black-forest-labs/flux.1-dev'
  AND capability = 'image';

-- 2) 快速档：FLUX.1-schnell（预览/批量）
INSERT INTO ai_model_config (
    id, provider, model_id, model_name, capability, invoke_url,
    default_steps, max_steps, protocol, enabled, sort_order, remark, created_at, updated_at
)
SELECT
    3100000000000000002,
    'nvidia',
    'black-forest-labs/flux.1-schnell',
    'FLUX.1-schnell（快速）',
    'image',
    'https://ai.api.nvidia.com/v1/genai/black-forest-labs/flux.1-schnell',
    4,
    8,
    'nvidia-flux',
    1,
    20,
    '极速蒸馏，适合预览',
    NOW(3),
    NOW(3)
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM ai_model_config
    WHERE provider = 'nvidia'
      AND model_id = 'black-forest-labs/flux.1-schnell'
      AND capability = 'image'
);

UPDATE ai_model_config
SET model_name = 'FLUX.1-schnell（快速）',
    invoke_url = 'https://ai.api.nvidia.com/v1/genai/black-forest-labs/flux.1-schnell',
    default_steps = 4,
    max_steps = 8,
    protocol = COALESCE(NULLIF(protocol, ''), 'nvidia-flux'),
    enabled = 1,
    sort_order = 20,
    remark = '极速蒸馏，适合预览',
    updated_at = NOW(3)
WHERE provider = 'nvidia'
  AND model_id = 'black-forest-labs/flux.1-schnell'
  AND capability = 'image';
