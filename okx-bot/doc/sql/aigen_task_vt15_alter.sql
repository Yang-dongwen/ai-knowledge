-- VT-1.5：出图 prompt 润色开关
USE okx_bot;

ALTER TABLE aigen_task ADD COLUMN enhance_image_prompt TINYINT DEFAULT 0 COMMENT '1=出图前润色 prompt' AFTER image_model;
