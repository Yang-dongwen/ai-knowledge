package com.dwcode.okxbot.kb.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KbPdfExportServiceTest {

    @Test
    void safeFilenameStripsIllegalChars() {
        assertEquals("a_b_c", KbPdfExportService.safeFilename("a/b:c"));
        assertEquals("未命名笔记", KbPdfExportService.safeFilename("   "));
    }
}
