package com.dwcode.okxbot.storage;

import com.dwcode.okxbot.storage.config.StorageProperties;
import com.dwcode.okxbot.storage.dto.MediaUrlResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaUrlServiceTest {

    @Mock
    private ObjectStoragePort objectStorage;

    private StorageProperties props;
    private MediaUrlService service;

    @BeforeEach
    void setUp() {
        props = new StorageProperties();
        props.setServeMode("presign");
        props.getR2().setPresignTtlSeconds(600);
        service = new MediaUrlService(objectStorage, props);
    }

    @Test
    void presignWhenR2AndObjectKey() {
        when(objectStorage.providerId()).thenReturn("r2");
        when(objectStorage.exists("dev/video/1/t/video.mp4")).thenReturn(true);
        when(objectStorage.presignGet(eq("dev/video/1/t/video.mp4"), any(Duration.class), eq(false), any()))
                .thenReturn(URI.create("https://r2.example/video.mp4?X-Amz-Signature=abc"));

        MediaUrlResponse r = service.resolve(
                "dev/video/1/t/video.mp4",
                "/api/v1/video/tasks/1/video",
                false,
                "video.mp4");

        assertEquals("presign", r.getMode());
        assertTrue(r.getUrl().startsWith("https://"));
        assertTrue(r.getExpiresAtMs() > System.currentTimeMillis());
        assertEquals("/api/v1/video/tasks/1/video", r.getProxyPath());
    }

    @Test
    void proxyWhenLocalPath() {
        when(objectStorage.providerId()).thenReturn("r2");
        MediaUrlResponse r = service.resolve(
                "D:\\data\\video.mp4",
                "/api/v1/video/tasks/1/video",
                false,
                "video.mp4");
        assertEquals("proxy", r.getMode());
        assertEquals("/api/v1/video/tasks/1/video", r.getUrl());
    }

    @Test
    void proxyWhenServeModeProxy() {
        props.setServeMode("proxy");
        when(objectStorage.providerId()).thenReturn("r2");
        MediaUrlResponse r = service.resolve(
                "dev/video/1/t/video.mp4",
                "/api/v1/video/tasks/1/video",
                false,
                "video.mp4");
        assertEquals("proxy", r.getMode());
    }
}
