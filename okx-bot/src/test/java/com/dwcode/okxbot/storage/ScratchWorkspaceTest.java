package com.dwcode.okxbot.storage;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.storage.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScratchWorkspaceTest {

    @TempDir
    Path temp;

    private ScratchWorkspace scratch;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.getScratch().setRoot(temp.resolve("scratch").toString());
        scratch = new ScratchWorkspace(props);
    }

    @Test
    void openAndCleanup() throws Exception {
        Path dir = scratch.openTaskScratch("video", "task1");
        assertTrue(Files.isDirectory(dir));
        Files.writeString(dir.resolve("tmp.txt"), "x");
        assertTrue(Files.isRegularFile(dir.resolve("tmp.txt")));

        int n = scratch.cleanupScratch("video", "task1");
        assertTrue(n >= 1);
        assertFalse(Files.isDirectory(dir));
    }

    @Test
    void rejectBadModule() {
        assertThrows(BusinessException.class,
                () -> scratch.openTaskScratch("evil", "t1"));
    }
}
