package com.dwcode.okxbot.storage;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.storage.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectKeyBuilderTest {

    private ObjectKeyBuilder builder;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setEnvPrefix("prod");
        builder = new ObjectKeyBuilder(props);
    }

    @Test
    void buildNormalKey() {
        String key = builder.build("video", 1001L, "2079167568778956802", "video.browser.mp4");
        assertEquals("prod/video/1001/2079167568778956802/video.browser.mp4", key);
    }

    @Test
    void buildNestedRelative() {
        String key = builder.build("aigen", 1L, "t1", "assets/images/shot_01.jpg");
        assertEquals("prod/aigen/1/t1/assets/images/shot_01.jpg", key);
    }

    @Test
    void taskPrefixEndsWithSlash() {
        assertEquals("prod/imggen/9/abc/", builder.taskPrefix("imggen", 9L, "abc"));
    }

    @Test
    void rejectPathTraversal() {
        assertThrows(BusinessException.class,
                () -> builder.build("video", 1L, "t1", "../etc/passwd"));
        assertThrows(BusinessException.class,
                () -> builder.build("video", 1L, "t1", "a/../../b"));
    }

    @Test
    void rejectBadModule() {
        assertThrows(BusinessException.class,
                () -> builder.build("other", 1L, "t1", "a.mp4"));
    }

    @Test
    void rejectNegativeUserId() {
        assertThrows(BusinessException.class,
                () -> builder.build("video", -1L, "t1", "a.mp4"));
    }

    @Test
    void allowZeroUserIdForLegacy() {
        assertEquals("prod/video/0/t1/a.mp4", builder.build("video", 0L, "t1", "a.mp4"));
    }

    @Test
    void looksLikeLocalAbsolutePath() {
        assertTrue(ObjectKeyBuilder.looksLikeLocalAbsolutePath("D:/data/video/1/video.mp4"));
        assertTrue(ObjectKeyBuilder.looksLikeLocalAbsolutePath("C:\\data\\a.mp4"));
        assertTrue(ObjectKeyBuilder.looksLikeLocalAbsolutePath("/var/data/a.mp4"));
        assertFalse(ObjectKeyBuilder.looksLikeLocalAbsolutePath("prod/video/1/t/video.mp4"));
        assertFalse(ObjectKeyBuilder.looksLikeLocalAbsolutePath(null));
    }
}
