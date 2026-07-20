package com.dwcode.okxbot.video.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoCookieServiceTest {

    @Test
    void parseCookieHeader() {
        List<String[]> pairs = VideoCookieService.parseCookieHeader(
                "Cookie: ttwid=abc; s_v_web_id=verify_x; sid_tt=yy");
        // Note: parseCookieHeader itself doesn't strip Cookie: — upload() does
        assertEquals(3, VideoCookieService.parseCookieHeader(
                "ttwid=abc; s_v_web_id=verify_x; sid_tt=yy").size());
        assertEquals("ttwid", VideoCookieService.parseCookieHeader("ttwid=abc").get(0)[0]);
        assertEquals("abc", VideoCookieService.parseCookieHeader("ttwid=abc").get(0)[1]);
    }
}
