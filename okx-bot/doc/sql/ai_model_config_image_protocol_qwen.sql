-- 清理 Qwen 文生图相关配置（系统已移除 Qwen 文生图适配）
-- 可选执行：删除 capability=image 且 model_id/url 含 qwen 的模型行
USE okx_bot;

DELETE FROM ai_model_config
WHERE capability = 'image'
  AND (
    model_id LIKE '%qwen%'
    OR model_id LIKE '%Qwen%'
    OR IFNULL(invoke_url, '') LIKE '%qwen%'
    OR IFNULL(protocol, '') LIKE '%qwen%'
  );

-- 将生图协议统一为 nvidia-flux（可选）
UPDATE ai_model_config
SET protocol = 'nvidia-flux'
WHERE capability = 'image'
  AND (protocol IS NULL OR protocol = '' OR protocol LIKE '%openai%' OR protocol LIKE '%qwen%')
  AND (model_id LIKE '%flux%' OR IFNULL(invoke_url, '') LIKE '%flux%' OR IFNULL(invoke_url, '') LIKE '%black-forest%');
