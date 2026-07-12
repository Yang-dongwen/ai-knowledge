package com.dwcode.okxbot.video.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwcode.okxbot.video.entity.AiModelConfigEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * LLM 模型配置 Mapper。
 */
@Mapper
public interface AiModelConfigMapper extends BaseMapper<AiModelConfigEntity> {
}
