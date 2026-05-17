package com.dwcode.okxbot.common.exception;

/**
 * 订单相关异常。
 */
public class OrderException extends BusinessException {

    public OrderException(String message) {
        super(30001, message);
    }

    public OrderException(int code, String message) {
        super(code, message);
    }
}
