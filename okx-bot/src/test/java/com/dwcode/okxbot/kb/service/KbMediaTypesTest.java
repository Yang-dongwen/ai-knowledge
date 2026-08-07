package com.dwcode.okxbot.kb.service;

import com.dwcode.okxbot.kb.entity.KbFileEntity;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void neverReEmitStoredHtmlOrSvgAsContentType() {
        KbFileEntity html = new KbFileEntity();
        html.setOriginalName("note.html");
        html.setContentType("text/html");
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, KbMediaTypes.resolve(html, null));
        assertEquals(MediaType.APPLICATION_OCTET_STREAM,
                KbMediaTypes.responseMediaType(KbMediaTypes.resolve(html, null)));
        assertFalse(KbMediaTypes.isSafeInline(KbMediaTypes.resolve(html, null)));

        KbFileEntity svg = new KbFileEntity();
        svg.setOriginalName("icon.svg");
        svg.setContentType("image/svg+xml");
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, KbMediaTypes.resolve(svg, null));
        assertFalse(KbMediaTypes.isSafeInline(MediaType.parseMediaType("image/svg+xml")));
        assertEquals(MediaType.APPLICATION_OCTET_STREAM,
                KbMediaTypes.responseMediaType(MediaType.parseMediaType("image/svg+xml")));
    }

    @Test
    void rasterAndPdfRemainSafeInline() {
        assertTrue(KbMediaTypes.isSafeInline(MediaType.IMAGE_PNG));
        assertTrue(KbMediaTypes.isSafeInline(MediaType.APPLICATION_PDF));
        assertTrue(KbMediaTypes.isSafeInline(MediaType.parseMediaType("video/mp4")));
        assertEquals(MediaType.IMAGE_PNG, KbMediaTypes.responseMediaType(MediaType.IMAGE_PNG));
    }

    @Test
    void baseMimeAndBlockedUploadExt() {
        assertEquals("text/html", KbFileService.baseMime("text/html; charset=utf-8"));
        assertEquals("image/svg+xml", KbFileService.baseMime("image/svg+xml"));
    }
}
