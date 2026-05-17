package com.dwcode.okxbot.trading.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwcode.okxbot.trading.order.entity.TradeOrderEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TradeOrderMapper extends BaseMapper<TradeOrderEntity> {
}
