package com.dwcode.okxbot.aigen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwcode.okxbot.aigen.entity.AigenTaskEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 视频生成任务 Mapper。
 */
@Mapper
public interface AigenTaskMapper extends BaseMapper<AigenTaskEntity> {
}
