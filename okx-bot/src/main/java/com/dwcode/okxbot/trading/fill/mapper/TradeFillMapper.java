package com.dwcode.okxbot.trading.fill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwcode.okxbot.trading.fill.entity.TradeFillEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TradeFillMapper extends BaseMapper<TradeFillEntity> {
}
