package com.dwcode.okxbot.blog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SlugUtilTest {

    @Test
    void asciiTitle() {
        assertEquals("hello-world", SlugUtil.fromTitle("Hello World", "post-1"));
    }

    @Test
    void chineseFallsBack() {
        assertEquals("post-99", SlugUtil.fromTitle("你好世界", "post-99"));
    }

    @Test
    void blankUsesFallback() {
        assertEquals("post", SlugUtil.fromTitle("   ", "post"));
    }
}
