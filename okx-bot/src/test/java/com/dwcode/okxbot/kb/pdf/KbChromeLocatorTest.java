package com.dwcode.okxbot.kb.pdf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbChromeLocatorTest {

    @TempDir
    Path dir;

    @Test
    void resolvePrefersConfiguredExistingFile() throws Exception {
        Path exe = dir.resolve("msedge.exe");
        Files.writeString(exe, "x");
        assertEquals(exe.toAbsolutePath().toString(), KbChromeLocator.resolve(exe.toString()));
    }

    @Test
    void missingConfiguredPathFallsBackToSystemBrowser() {
        String found = KbChromeLocator.resolve(dir.resolve("missing.exe").toString());
        if (found != null) {
            assertTrue(Files.isRegularFile(Path.of(found)));
        }
    }

    @Test
    void windowsCandidatesIncludeEdgeAndChrome() {
        if (!KbChromeLocator.isWindows()) {
            return;
        }
        List<Path> cs = KbChromeLocator.candidates();
        assertTrue(cs.stream().anyMatch(p -> p.toString().replace('\\', '/').endsWith("msedge.exe")));
        assertTrue(cs.stream().anyMatch(p -> p.toString().replace('\\', '/').endsWith("chrome.exe")));
    }
}
