package com.dwcode.okxbot.pay.channel.alipay;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AlipayPaymentChannelParseTest {

    @Test
    void parseFormBody() {
        Map<String, String> m = AlipayPaymentChannel.parseFormBody(
                "out_trade_no=P123&trade_status=TRADE_SUCCESS&total_amount=29.00");
        assertEquals("P123", m.get("out_trade_no"));
        assertEquals("TRADE_SUCCESS", m.get("trade_status"));
        assertEquals("29.00", m.get("total_amount"));
    }

    @Test
    void normalizeKeyStripsPem() {
        String pem = """
                -----BEGIN PRIVATE KEY-----
                ABCD
                EFGH
                -----END PRIVATE KEY-----
                """;
        assertEquals("ABCDEFGH", AlipayClientFactory.normalizeKey(pem));
    }
}
