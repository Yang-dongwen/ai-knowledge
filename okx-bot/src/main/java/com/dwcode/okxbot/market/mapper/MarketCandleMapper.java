package com.dwcode.okxbot.market.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwcode.okxbot.market.entity.MarketCandleEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MarketCandleMapper extends BaseMapper<MarketCandleEntity> {
}
