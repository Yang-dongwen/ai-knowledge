package com.dwcode.okxbot.common.exception;

/**
 * 策略相关异常。
 */
public class StrategyException extends BusinessException {

    public StrategyException(String message) {
        super(20001, message);
    }

    public StrategyException(int code, String message) {
        super(code, message);
    }
}
