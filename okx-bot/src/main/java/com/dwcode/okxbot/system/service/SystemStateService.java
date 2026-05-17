package com.dwcode.okxbot.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.common.enums.SystemStatusEnum;
import com.dwcode.okxbot.system.entity.SystemStateEntity;
import com.dwcode.okxbot.system.mapper.SystemStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 系统状态服务。
 *
 * 职责：
 * 1. 查询系统运行状态
 * 2. 一键停止
 * 3. 恢复运行
 * 4. 异常时自动停止
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemStateService {

    private static final String TRADING_STATUS_KEY = "TRADING_STATUS";

    private final SystemStateMapper systemStateMapper;

    /**
     * 查询系统是否已停止。
     */
    public boolean isStopped() {
        SystemStateEntity state = getState(TRADING_STATUS_KEY);
        return state != null && SystemStatusEnum.STOPPED.name().equals(state.getStateValue());
    }

    /**
     * 获取系统状态。
     */
    public String getSystemStatus() {
        SystemStateEntity state = getState(TRADING_STATUS_KEY);
        return state != null ? state.getStateValue() : SystemStatusEnum.RUNNING.name();
    }

    /**
     * 一键停止交易。
     */
    public void stopTrading() {
        updateState(TRADING_STATUS_KEY, SystemStatusEnum.STOPPED.name(), "交易系统运行状态");
        log.info("系统已停止交易");
    }

    /**
     * 恢复交易。
     */
    public void resumeTrading() {
        updateState(TRADING_STATUS_KEY, SystemStatusEnum.RUNNING.name(), "交易系统运行状态");
        log.info("系统已恢复交易");
    }

    private SystemStateEntity getState(String key) {
        return systemStateMapper.selectOne(
                new LambdaQueryWrapper<SystemStateEntity>()
                        .eq(SystemStateEntity::getStateKey, key)
        );
    }

    private void updateState(String key, String value, String description) {
        SystemStateEntity state = getState(key);
        if (state == null) {
            state = new SystemStateEntity();
            state.setStateKey(key);
            state.setStateValue(value);
            state.setDescription(description);
            state.setCreatedAt(LocalDateTime.now());
            state.setUpdatedAt(LocalDateTime.now());
            systemStateMapper.insert(state);
        } else {
            state.setStateValue(value);
            state.setUpdatedAt(LocalDateTime.now());
            systemStateMapper.updateById(state);
        }
    }
}
