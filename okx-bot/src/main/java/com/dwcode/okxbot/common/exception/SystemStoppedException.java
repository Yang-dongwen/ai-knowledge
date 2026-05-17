package com.dwcode.okxbot.common.exception;

/**
 * 系统已停止异常。
 */
public class SystemStoppedException extends BusinessException {

    public SystemStoppedException() {
        super(40001, "系统已停止，禁止新下单");
    }
}
