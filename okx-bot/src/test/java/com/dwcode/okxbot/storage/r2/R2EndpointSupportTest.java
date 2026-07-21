package com.dwcode.okxbot.storage.r2;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.storage.config.StorageProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R2EndpointSupportTest {

    @Test
    void resolveFromAccountId() {
        StorageProperties.R2 r2 = new StorageProperties.R2();
        r2.setAccountId("abc123");
        assertEquals("https://abc123.r2.cloudflarestorage.com", R2EndpointSupport.resolveEndpoint(r2));
    }

    @Test
    void resolveExplicitEndpointTrimsSlash() {
        StorageProperties.R2 r2 = new StorageProperties.R2();
        r2.setEndpoint("https://example.r2.cloudflarestorage.com/");
        assertEquals("https://example.r2.cloudflarestorage.com", R2EndpointSupport.resolveEndpoint(r2));
    }

    @Test
    void validateRequiresBucketAndKeys() {
        StorageProperties.R2 r2 = new StorageProperties.R2();
        r2.setAccountId("abc");
        assertThrows(BusinessException.class, () -> R2EndpointSupport.validate(r2));

        r2.setBucket("my-bucket");
        r2.setAccessKeyId("ak");
        r2.setSecretAccessKey("sk");
        R2EndpointSupport.validate(r2);
    }

    @Test
    void regionOrAuto() {
        StorageProperties.R2 r2 = new StorageProperties.R2();
        assertEquals("auto", R2EndpointSupport.regionOrAuto(r2));
        r2.setRegion("WNAM");
        assertEquals("wnam", R2EndpointSupport.regionOrAuto(r2));
    }

    @Test
    void normalizePrefixFromStorage() {
        assertTrue(R2S3ObjectStorage.normalizePrefix("dev/video/1/t").endsWith("/"));
        assertEquals("dev/video/1/t/a.mp4", R2S3ObjectStorage.normalizeKey("dev/video/1/t/a.mp4"));
    }
}
