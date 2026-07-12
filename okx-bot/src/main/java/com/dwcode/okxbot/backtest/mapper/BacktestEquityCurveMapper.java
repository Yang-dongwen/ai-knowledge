package com.dwcode.okxbot.backtest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwcode.okxbot.backtest.entity.BacktestEquityCurveEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 回测资金曲线 Mapper。
 */
@Mapper
public interface BacktestEquityCurveMapper extends BaseMapper<BacktestEquityCurveEntity> {
}
