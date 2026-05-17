package com.dwcode.okxbot.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * BigDecimal 工具类。
 * 所有金额、价格、数量计算必须使用此工具类。
 */
public final class BigDecimalUtil {

    private BigDecimalUtil() {
    }

    /** 价格精度 */
    public static final int PRICE_SCALE = 8;
    /** 数量精度 */
    public static final int QUANTITY_SCALE = 8;
    /** 金额精度 */
    public static final int AMOUNT_SCALE = 8;
    /** 百分比精度 */
    public static final int PCT_SCALE = 10;

    public static BigDecimal multiply(BigDecimal a, BigDecimal b) {
        return a.multiply(b).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal divide(BigDecimal a, BigDecimal b) {
        return a.divide(b, AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 判断 a 是否大于 b。
     */
    public static boolean gt(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) > 0;
    }

    /**
     * 判断 a 是否大于等于 b。
     */
    public static boolean gte(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0;
    }

    /**
     * 判断 a 是否小于 b。
     */
    public static boolean lt(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) < 0;
    }

    /**
     * 判断 a 是否小于等于 b。
     */
    public static boolean lte(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0;
    }

    /**
     * 判断是否为正数。
     */
    public static boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * 计算均值。
     */
    public static BigDecimal average(java.util.List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return divide(sum, BigDecimal.valueOf(values.size()));
    }
}
