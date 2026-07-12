-- LLM 模型配置表 + 初始数据（可按需调整）
USE okx_bot;

CREATE TABLE IF NOT EXISTS ai_model_config (
    id BIGINT NOT NULL COMMENT '主键',
    provider VARCHAR(64) NOT NULL COMMENT '供应商标识，对应 ai.providers 的 key',
    model_id VARCHAR(128) NOT NULL COMMENT 'API模型ID',
    model_name VARCHAR(128) NOT NULL COMMENT '展示名称',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用 1是 0否',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序，越小越靠前',
    remark VARCHAR(255) COMMENT '备注',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_provider_model (provider, model_id),
    INDEX idx_enabled_sort (enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM模型配置表';

-- 初始模型（ID 用雪花简化：固定数字，若冲突可改）
-- 供应商 api-key 仍在 application.yml 的 ai.providers 中配置
INSERT INTO ai_model_config (id, provider, model_id, model_name, enabled, sort_order, remark, created_at, updated_at)
VALUES
(3000000000000000001, 'nvidia', 'deepseek-ai/deepseek-v4-flash', 'DeepSeek V4 Flash（快）', 1, 10, '推荐默认', NOW(3), NOW(3)),
(3000000000000000002, 'nvidia', 'deepseek-ai/deepseek-r1', 'DeepSeek R1（强推理）', 1, 20, NULL, NOW(3), NOW(3)),
(3000000000000000003, 'nvidia', 'z-ai/glm-5.2', 'GLM-5.2（智谱旗舰）', 1, 30, 'build.nvidia.com', NOW(3), NOW(3)),
(3000000000000000004, 'nvidia', 'z-ai/glm-5.1', 'GLM-5.1', 1, 40, NULL, NOW(3), NOW(3)),
(3000000000000000005, 'nvidia', 'minimaxai/minimax-m2.7', 'MiniMax M2.7', 1, 50, NULL, NOW(3), NOW(3)),
(3000000000000000006, 'nvidia', 'minimaxai/minimax-m3', 'MiniMax M3 Preview', 1, 60, NULL, NOW(3), NOW(3)),
(3000000000000000007, 'nvidia', 'meta/llama-3.3-70b-instruct', 'Llama 3.3 70B Instruct', 1, 70, NULL, NOW(3), NOW(3)),
(3000000000000000008, 'nvidia', 'qwen/qwen2.5-72b-instruct', 'Qwen2.5 72B Instruct（中文强）', 1, 80, NULL, NOW(3), NOW(3)),
(3000000000000000009, 'nvidia', 'meta/llama-3.1-8b-instruct', 'Llama 3.1 8B Instruct（轻量）', 1, 90, NULL, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE model_name = VALUES(model_name), updated_at = NOW(3);
