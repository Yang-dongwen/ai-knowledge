package com.dwcode.okxbot.pay.util;

import com.dwcode.okxbot.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MoneyUtilTest {

    @Test
    void yuanToCentsExactTwoDecimals() {
        assertEquals(2900L, MoneyUtil.yuanStringToCents("29.00"));
        assertEquals(19900L, MoneyUtil.yuanStringToCents("199.00"));
        assertEquals(1L, MoneyUtil.yuanStringToCents("0.01"));
    }

    @Test
    void yuanScaleOneStillOkViaExact() {
        // 29.0 → scale 1，movePointRight(2) = 290.0 → exact 290
        assertEquals(2900L, MoneyUtil.yuanStringToCents("29.0"));
        assertEquals(2900L, MoneyUtil.yuanStringToCents("29"));
    }

    @Test
    void rejectsTooManyDecimals() {
        assertThrows(BusinessException.class, () -> MoneyUtil.yuanStringToCents("29.001"));
    }

    @Test
    void centsToYuan() {
        assertEquals("29.00", MoneyUtil.centsToYuan(2900));
        assertEquals("199.00", MoneyUtil.centsToYuan(19900));
    }
}
