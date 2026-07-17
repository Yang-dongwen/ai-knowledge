package com.dwcode.okxbot.pay.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 商户订单号：P + yyyyMMddHHmmss + 数字后缀，总长 ≤32。
 */
public final class OrderNoGenerator {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private OrderNoGenerator() {
    }

    public static String next() {
        String ts = LocalDateTime.now().format(FMT);
        long suffix = ThreadLocalRandom.current().nextLong(0, 1_000_000_0000L);
        String s = "P" + ts + String.format("%010d", suffix);
        if (s.length() > 32) {
            return s.substring(0, 32);
        }
        return s;
    }
}
