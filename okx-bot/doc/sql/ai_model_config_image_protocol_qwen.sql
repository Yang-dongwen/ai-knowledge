-- 生图协议字段 + 预置 Qwen-Image 模型
-- 在已有 ai_model_config 上执行（需已有 capability / invoke_url 列）
USE okx_bot;

-- protocol：nvidia-flux | nvidia-qwen | nvidia-openai-images
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_model_config'
      AND COLUMN_NAME = 'protocol'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE ai_model_config ADD COLUMN protocol VARCHAR(64) NULL COMMENT ''生图协议 nvidia-flux|nvidia-qwen|nvidia-openai-images'' AFTER max_steps',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 标记已有 FLUX 模型协议（若能匹配到）
UPDATE ai_model_config
SET protocol = 'nvidia-flux'
WHERE capability = 'image'
  AND (protocol IS NULL OR protocol = '')
  AND (model_id LIKE '%flux%' OR invoke_url LIKE '%flux%' OR invoke_url LIKE '%black-forest%');

-- 修正错误的 genai 路径（历史 seed 会 404）
UPDATE ai_model_config
SET invoke_url = 'https://ai.api.nvidia.com/v1/images/generations',
    protocol = 'nvidia-qwen',
    default_steps = 30,
    max_steps = 100,
    remark = '官方为 Visual GenAI NIM：POST /v1/images/generations；云端托管可能未开通，本地请改为 http://127.0.0.1:8000/v1/images/generations'
WHERE capability = 'image'
  AND (model_id LIKE '%qwen%' OR invoke_url LIKE '%/genai/qwen%');

-- Qwen-Image（OpenAI Images 兼容；自托管 NIM 推荐改 base 为本地）
INSERT INTO ai_model_config (
    id, provider, model_id, model_name, capability, invoke_url,
    default_steps, max_steps, protocol, enabled, sort_order, remark, created_at, updated_at
)
SELECT
    3000000000000000011, 'nvidia', 'qwen/qwen-image', 'Qwen-Image（多语文字）',
    'image', 'http://127.0.0.1:8000/v1/images/generations',
    30, 100, 'nvidia-qwen', 1, 30,
    '官方 OpenAPI：自托管 NIM POST /v1/images/generations。见 docs.nvidia.com/nim/visual-genai/latest/api/qwen-image.html',
    NOW(3), NOW(3)
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM ai_model_config
    WHERE provider = 'nvidia' AND model_id = 'qwen/qwen-image' AND capability = 'image'
);

-- Qwen-Image-2512
INSERT INTO ai_model_config (
    id, provider, model_id, model_name, capability, invoke_url,
    default_steps, max_steps, protocol, enabled, sort_order, remark, created_at, updated_at
)
SELECT
    3000000000000000012, 'nvidia', 'qwen/qwen-image-2512', 'Qwen-Image-2512',
    'image', 'http://127.0.0.1:8000/v1/images/generations',
    30, 100, 'nvidia-qwen', 1, 40,
    'model 字段传 qwen/qwen-image-2512；需本机或内网已部署对应 NIM',
    NOW(3), NOW(3)
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM ai_model_config
    WHERE provider = 'nvidia' AND model_id = 'qwen/qwen-image-2512' AND capability = 'image'
);
