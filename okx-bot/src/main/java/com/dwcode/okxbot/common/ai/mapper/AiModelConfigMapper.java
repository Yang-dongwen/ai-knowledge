package com.dwcode.okxbot.common.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwcode.okxbot.common.ai.entity.AiModelConfigEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * LLM 模型配置 Mapper。
 */
@Mapper
public interface AiModelConfigMapper extends BaseMapper<AiModelConfigEntity> {
}
