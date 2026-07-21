package com.dwcode.okxbot.imggen.service;

import com.dwcode.okxbot.imggen.config.ImgGenProperties;
import com.dwcode.okxbot.imggen.entity.ImgGenTaskEntity;
import com.dwcode.okxbot.storage.LocalObjectStorage;
import com.dwcode.okxbot.storage.ObjectKeyBuilder;
import com.dwcode.okxbot.storage.ScratchWorkspace;
import com.dwcode.okxbot.storage.config.StorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImgGenStorageServicePersistTest {

    @TempDir
    Path temp;

    private ImgGenStorageService storage;
    private LocalObjectStorage objectStorage;

    @BeforeEach
    void setUp() {
        StorageProperties sp = new StorageProperties();
        sp.setEnvPrefix("dev");
        sp.getLocal().setRoot(temp.resolve("objects").toString());
        sp.getScratch().setRoot(temp.resolve("scratch").toString());
        sp.getCleanup().setScratchOnSuccess(true);

        ImgGenProperties props = new ImgGenProperties();
        props.setWorkDir(temp.resolve("legacy-imggen").toString());

        objectStorage = new LocalObjectStorage(sp);
        storage = new ImgGenStorageService(
                props, objectStorage, new ObjectKeyBuilder(sp), new ScratchWorkspace(sp), sp, new ObjectMapper());
    }

    @Test
    void persistUploadsOutputs() throws Exception {
        ImgGenTaskEntity task = new ImgGenTaskEntity();
        task.setId(55L);
        task.setUserId(3L);

        Path dir = storage.ensureTaskDir("55");
        Path img = dir.resolve("outputs").resolve("img-01.png");
        Files.writeString(img, "png");
        task.setCoverPath(img.toAbsolutePath().toString());
        task.setResultJson("{\"images\":[{\"index\":1,\"path\":\"outputs/img-01.png\"}]}");

        storage.persistAndCleanupAfterSuccess(task);

        assertEquals("dev/imggen/3/55/outputs/img-01.png", task.getCoverPath());
        assertTrue(objectStorage.exists(task.getCoverPath()));
        assertTrue(storage.mediaAvailable(task.getCoverPath()));
        assertFalse(Files.isDirectory(dir));
    }
}
