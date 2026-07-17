package com.dwcode.okxbot.pay.enums;

import com.dwcode.okxbot.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PayChannelTest {

    @Test
    void normalize() {
        assertEquals("mock", PayChannel.normalize("MOCK"));
        assertEquals("alipay", PayChannel.normalize(" alipay "));
        assertThrows(BusinessException.class, () -> PayChannel.normalize("paypal"));
        assertThrows(BusinessException.class, () -> PayChannel.normalize(""));
    }
}
