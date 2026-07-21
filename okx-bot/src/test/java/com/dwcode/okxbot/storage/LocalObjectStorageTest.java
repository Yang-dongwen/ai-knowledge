package com.dwcode.okxbot.storage;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.storage.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalObjectStorageTest {

    @TempDir
    Path temp;

    private LocalObjectStorage storage;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.getLocal().setRoot(temp.resolve("objects").toString());
        storage = new LocalObjectStorage(props);
    }

    @Test
    void putBytesAndOpenStream() throws Exception {
        String key = "dev/video/1/t1/summary.json";
        storage.putBytes(key, "{\"a\":1}".getBytes(StandardCharsets.UTF_8), "application/json");
        assertTrue(storage.exists(key));
        assertEquals(7, storage.head(key).orElseThrow().getSizeBytes());
        try (var in = storage.openStream(key)) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("{\"a\":1}", body);
        }
    }

    @Test
    void putFileAndGetToFile() throws Exception {
        Path src = temp.resolve("src.mp4");
        Files.writeString(src, "fake-mp4");
        String key = "prod/aigen/2/t2/output.mp4";
        storage.put(key, src, "video/mp4");

        Path dest = temp.resolve("out.mp4");
        storage.getToFile(key, dest);
        assertEquals("fake-mp4", Files.readString(dest));
        assertEquals("video/mp4", storage.head(key).orElseThrow().getContentType());
    }

    @Test
    void deletePrefixRemovesTaskObjects() {
        storage.putBytes("dev/video/1/t1/a.mp4", new byte[]{1, 2}, null);
        storage.putBytes("dev/video/1/t1/b.json", new byte[]{3}, null);
        storage.putBytes("dev/video/1/t2/c.mp4", new byte[]{4}, null);

        int n = storage.deletePrefix("dev/video/1/t1/");
        assertTrue(n >= 2);
        assertFalse(storage.exists("dev/video/1/t1/a.mp4"));
        assertTrue(storage.exists("dev/video/1/t2/c.mp4"));
    }

    @Test
    void rejectKeyTraversal() {
        assertThrows(BusinessException.class,
                () -> storage.putBytes("../escape.txt", new byte[]{1}, null));
    }

    @Test
    void presignNotSupported() {
        storage.putBytes("dev/video/1/t/a.mp4", new byte[]{1}, null);
        assertThrows(BusinessException.class,
                () -> storage.presignGet("dev/video/1/t/a.mp4", Duration.ofMinutes(5), false, null));
    }

    @Test
    void missingObject404() {
        assertThrows(BusinessException.class,
                () -> storage.openStream("dev/video/1/t/missing.mp4"));
    }
}
