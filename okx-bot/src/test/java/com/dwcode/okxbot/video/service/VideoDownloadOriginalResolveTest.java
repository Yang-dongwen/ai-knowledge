package com.dwcode.okxbot.video.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VideoDownloadOriginalResolveTest {

    @Test
    void siblingOriginalKeysFromBrowser() {
        String[] keys = VideoProcessService.siblingOriginalObjectKeys("dev/video/1/t/video.browser.mp4");
        assertArrayEquals(new String[] {
                "dev/video/1/t/video.mp4",
                "dev/video/1/t/video.webm"
        }, keys);
    }

    @Test
    void siblingOriginalKeysEmptyWhenAlreadySource() {
        assertEquals(0, VideoProcessService.siblingOriginalObjectKeys("dev/video/1/t/video.mp4").length);
        assertEquals(0, VideoProcessService.siblingOriginalObjectKeys(null).length);
    }

    @Test
    void preferOriginalLocalUsesSiblingMp4(@TempDir Path dir) throws Exception {
        Path browser = dir.resolve("video.browser.mp4");
        Path source = dir.resolve("video.mp4");
        Files.write(browser, new byte[] { 1, 2, 3 });
        Files.write(source, new byte[] { 9, 9, 9, 9 });
        assertEquals(source, VideoProcessService.preferOriginalLocal(browser));
    }

    @Test
    void preferOriginalLocalKeepsNonBrowser(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("video.mp4");
        Files.write(source, new byte[] { 1 });
        assertEquals(source, VideoProcessService.preferOriginalLocal(source));
    }
}
