package com.dwcode.okxbot.backtest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwcode.okxbot.backtest.entity.BacktestTradeEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 回测交易明细 Mapper。
 */
@Mapper
public interface BacktestTradeMapper extends BaseMapper<BacktestTradeEntity> {
}
