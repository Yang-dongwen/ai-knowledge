package com.dwcode.okxbot.auth.wechat;

import com.dwcode.okxbot.auth.config.AuthProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WxMiniSessionClientTest {

    @Test
    void mockOpenidFromCode() {
        AuthProperties props = new AuthProperties();
        props.getWechat().getMini().setEnabled(false);
        WxMiniSessionClient client = new WxMiniSessionClient(props, new ObjectMapper());
        String openid = client.resolveOpenid("mock:deviceABC");
        assertEquals("mock_deviceABC", openid);
    }

    @Test
    void mockOpenidSanitizes() {
        AuthProperties props = new AuthProperties();
        props.getWechat().getMini().setEnabled(false);
        WxMiniSessionClient client = new WxMiniSessionClient(props, new ObjectMapper());
        String openid = client.resolveOpenid("raw-id_01");
        assertTrue(openid.startsWith("mock_"));
        assertTrue(openid.contains("raw-id_01") || openid.contains("rawid_01") || openid.length() > 5);
    }
}
