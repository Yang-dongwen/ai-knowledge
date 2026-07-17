package com.dwcode.okxbot.pay.enums;

public final class PayOrderStatus {
    public static final String CREATED = "CREATED";
    public static final String PAYING = "PAYING";
    public static final String SUCCESS = "SUCCESS";
    public static final String CLOSED = "CLOSED";
    public static final String FAILED = "FAILED";

    private PayOrderStatus() {
    }

    public static boolean isOpen(String status) {
        return CREATED.equals(status) || PAYING.equals(status);
    }

    public static boolean isTerminal(String status) {
        return SUCCESS.equals(status) || CLOSED.equals(status) || FAILED.equals(status);
    }
}
