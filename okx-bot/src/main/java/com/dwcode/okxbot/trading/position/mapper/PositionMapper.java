package com.dwcode.okxbot.trading.position.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwcode.okxbot.trading.position.entity.PositionEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PositionMapper extends BaseMapper<PositionEntity> {
}
