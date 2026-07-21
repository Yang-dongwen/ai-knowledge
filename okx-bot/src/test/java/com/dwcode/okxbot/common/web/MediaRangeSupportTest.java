package com.dwcode.okxbot.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MediaRangeSupportTest {

    @Test
    void parseBytesRange() {
        Optional<MediaRangeSupport.ByteRange> r = MediaRangeSupport.parse("bytes=0-1023", 10000);
        assertTrue(r.isPresent());
        assertEquals(0, r.get().start());
        assertEquals(1023, r.get().endInclusive());
        assertEquals(1024, r.get().length());
    }

    @Test
    void parseOpenEnded() {
        Optional<MediaRangeSupport.ByteRange> r = MediaRangeSupport.parse("bytes=500-", 1000);
        assertTrue(r.isPresent());
        assertEquals(500, r.get().start());
        assertEquals(999, r.get().endInclusive());
    }

    @Test
    void buildPartialContent() throws Exception {
        byte[] data = new byte[1000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256);
        }
        ResponseEntity<Resource> resp = MediaRangeSupport.build(
                "bytes=10-19",
                data.length,
                "video/mp4",
                "t.mp4",
                (start, end) -> {
                    int len = (int) (end - start + 1);
                    return new ByteArrayInputStream(data, start.intValue(), len);
                });
        assertEquals(HttpStatus.PARTIAL_CONTENT, resp.getStatusCode());
        assertEquals("bytes 10-19/1000", resp.getHeaders().getFirst("Content-Range"));
        assertEquals("10", resp.getHeaders().getFirst("Content-Length"));
        try (InputStream in = resp.getBody().getInputStream()) {
            assertEquals(10, in.readAllBytes().length);
        }
    }
}
