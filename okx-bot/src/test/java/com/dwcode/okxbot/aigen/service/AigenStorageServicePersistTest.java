package com.dwcode.okxbot.aigen.service;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.entity.AigenTaskEntity;
import com.dwcode.okxbot.storage.LocalObjectStorage;
import com.dwcode.okxbot.storage.ObjectKeyBuilder;
import com.dwcode.okxbot.storage.ScratchWorkspace;
import com.dwcode.okxbot.storage.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AigenStorageServicePersistTest {

    @TempDir
    Path temp;

    private AigenStorageService storage;
    private LocalObjectStorage objectStorage;

    @BeforeEach
    void setUp() {
        StorageProperties sp = new StorageProperties();
        sp.setEnvPrefix("dev");
        sp.getLocal().setRoot(temp.resolve("objects").toString());
        sp.getScratch().setRoot(temp.resolve("scratch").toString());
        sp.getCleanup().setScratchOnSuccess(true);

        AigenProperties props = new AigenProperties();
        props.setWorkDir(temp.resolve("legacy-aigen").toString());

        objectStorage = new LocalObjectStorage(sp);
        storage = new AigenStorageService(
                props, objectStorage, new ObjectKeyBuilder(sp), new ScratchWorkspace(sp), sp);
    }

    @Test
    void persistUploadsOutputAndAssets() throws Exception {
        AigenTaskEntity task = new AigenTaskEntity();
        task.setId(88L);
        task.setUserId(2L);

        Path dir = storage.ensureTaskDir("88");
        Path out = dir.resolve("output.mp4");
        Files.write(out, new byte[2048]);
        Path jpg = dir.resolve("assets").resolve("visual").resolve("s1.jpg");
        Files.writeString(jpg, "jpg");
        task.setOutputPath(out.toAbsolutePath().toString());

        storage.persistAndCleanupAfterSuccess(task);

        assertEquals("dev/aigen/2/88/output.mp4", task.getOutputPath());
        assertTrue(objectStorage.exists(task.getOutputPath()));
        assertTrue(objectStorage.exists("dev/aigen/2/88/assets/visual/s1.jpg"));
        assertTrue(storage.mediaAvailable(task.getOutputPath()));
        assertFalse(Files.isDirectory(dir));
    }
}
