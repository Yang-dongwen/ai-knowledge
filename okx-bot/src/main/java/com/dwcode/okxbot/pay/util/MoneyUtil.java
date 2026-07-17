package com.dwcode.okxbot.pay.util;

import com.dwcode.okxbot.common.exception.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MoneyUtil {

    private MoneyUtil() {
    }

    public static String centsToYuan(int cents) {
        return BigDecimal.valueOf(cents)
                .movePointLeft(2)
                .setScale(2, RoundingMode.UNNECESSARY)
                .toPlainString();
    }

    /**
     * 支付宝元字符串 → 分；必须恰好 2 位小数。
     */
    public static long yuanStringToCents(String yuan) {
        if (yuan == null || yuan.isBlank()) {
            throw new BusinessException(400, "金额为空");
        }
        BigDecimal bd;
        try {
            bd = new BigDecimal(yuan.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "金额格式非法: " + yuan);
        }
        if (bd.scale() > 2) {
            throw new BusinessException(400, "金额小数位超过 2 位: " + yuan);
        }
        try {
            return bd.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException e) {
            throw new BusinessException(400, "金额无法精确转为分: " + yuan);
        }
    }
}
