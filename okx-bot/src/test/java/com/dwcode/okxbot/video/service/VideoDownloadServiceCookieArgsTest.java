package com.dwcode.okxbot.video.service;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.config.VideoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cookie / 错误增强逻辑的轻量单测（不启动 Spring）。
 */
class VideoDownloadServiceCookieArgsTest {

    private VideoProperties props;
    private VideoDownloadService service;

    @BeforeEach
    void setUp() {
        props = new VideoProperties();
        service = new VideoDownloadService(props, null, null, null);
    }

    @Test
    void appendCookiesFromBrowserForDouyin() {
        props.getDownload().setCookiesFromBrowser("edge");
        List<String> cmd = new ArrayList<>();
        service.appendCookieArgs(cmd, "https://www.douyin.com/video/123");
        assertEquals(List.of("--cookies-from-browser", "edge"), cmd);
    }

    @Test
    void skipCookiesForBilibili() {
        props.getDownload().setCookiesFromBrowser("edge");
        List<String> cmd = new ArrayList<>();
        service.appendCookieArgs(cmd, "https://www.bilibili.com/video/BVxxxx");
        assertTrue(cmd.isEmpty());
    }

    @Test
    void cookiesFileTakesPriority(@TempDir Path dir) throws Exception {
        Path cookie = dir.resolve("cookies.txt");
        Files.writeString(cookie, "# Netscape HTTP Cookie File\n");
        props.getDownload().setCookiesFromBrowser("chrome");
        props.getDownload().setCookiesFile(cookie.toString());
        List<String> cmd = new ArrayList<>();
        service.appendCookieArgs(cmd, "https://www.douyin.com/video/1");
        assertEquals(2, cmd.size());
        assertEquals("--cookies", cmd.get(0));
        assertTrue(cmd.get(1).contains("cookies.txt"));
        assertFalse(cmd.contains("--cookies-from-browser"));
    }

    @Test
    void enrichUnsupportedUrl() {
        BusinessException raw = new BusinessException("外部命令失败 — ERROR: Unsupported URL: https://x");
        BusinessException enriched = service.enrichDownloadError(
                "https://www.douyin.com/user/self?modal_id=1", raw);
        assertTrue(enriched.getMessage().contains("不支持的视频链接"));
    }

    @Test
    void enrichFreshCookies() {
        BusinessException raw = new BusinessException(
                "外部命令失败 — ERROR: [Douyin] xxx: Fresh cookies (not necessarily logged in) are needed");
        BusinessException enriched = service.enrichDownloadError(
                "https://www.douyin.com/video/123", raw);
        assertTrue(enriched.getMessage().contains("cookies-from-browser")
                || enriched.getMessage().contains("Cookie"));
    }
}
