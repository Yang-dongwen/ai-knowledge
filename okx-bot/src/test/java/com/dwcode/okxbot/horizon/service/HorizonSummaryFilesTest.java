package com.dwcode.okxbot.horizon.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HorizonSummaryFilesTest {

    @TempDir
    Path dir;

    @Test
    void readsLatestZhFile() throws Exception {
        Files.writeString(dir.resolve("horizon-2026-08-13-zh.md"), "# 日报\nhello", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("horizon-2026-08-12-zh.md"), "# 旧", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("horizon-2026-08-13-en.md"), "# EN", StandardCharsets.UTF_8);
        HorizonSummaryFiles files = new HorizonSummaryFiles(dir);

        var latest = files.latest("zh", null).orElseThrow();
        assertEquals("2026-08-13", latest.getDate());
        assertTrue(latest.getMarkdown().contains("hello"));
        assertEquals(2, files.recent("zh", 10).size());
        assertEquals("2026-08-12", files.latest("zh", "2026-08-12").orElseThrow().getDate());
    }
}
