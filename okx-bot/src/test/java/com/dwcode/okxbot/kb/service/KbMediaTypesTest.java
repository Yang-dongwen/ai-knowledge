package com.dwcode.okxbot.kb.service;

import com.dwcode.okxbot.kb.entity.KbFileEntity;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KbMediaTypesTest {

    @Test
    void sniffPngEvenWhenNamedJpg() {
        KbFileEntity e = new KbFileEntity();
        e.setOriginalName("dwcode.cloud.jpg");
        e.setContentType("image/jpeg");
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        assertEquals(MediaType.IMAGE_PNG, KbMediaTypes.resolve(e, png));
    }

    @Test
    void sniffJpeg() {
        KbFileEntity e = new KbFileEntity();
        e.setOriginalName("a.png");
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        assertEquals(MediaType.IMAGE_JPEG, KbMediaTypes.resolve(e, jpeg));
    }

    @Test
    void fallbackToExtensionWhenNoHeader() {
        KbFileEntity e = new KbFileEntity();
        e.setOriginalName("pic.webp");
        assertEquals(MediaType.parseMediaType("image/webp"), KbMediaTypes.resolve(e, null));
    }
}
