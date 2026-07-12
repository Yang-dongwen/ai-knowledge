package com.dwcode.okxbot.backtest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwcode.okxbot.backtest.entity.BacktestTaskEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 回测任务 Mapper。
 */
@Mapper
public interface BacktestTaskMapper extends BaseMapper<BacktestTaskEntity> {
}
