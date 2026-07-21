package com.dwcode.okxbot.aigen;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.service.AigenStorageService;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.storage.LocalObjectStorage;
import com.dwcode.okxbot.storage.ObjectKeyBuilder;
import com.dwcode.okxbot.storage.ScratchWorkspace;
import com.dwcode.okxbot.storage.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StoryboardPathSecurityTest {

    @TempDir
    Path temp;

    AigenStorageService storage;

    @BeforeEach
    void setUp() {
        AigenProperties props = new AigenProperties();
        props.setWorkDir(temp.resolve("legacy-aigen").toString());

        StorageProperties storageProps = new StorageProperties();
        storageProps.setEnvPrefix("dev");
        storageProps.getLocal().setRoot(temp.resolve("objects").toString());
        storageProps.getScratch().setRoot(temp.resolve("scratch").toString());

        LocalObjectStorage objectStorage = new LocalObjectStorage(storageProps);
        ObjectKeyBuilder keys = new ObjectKeyBuilder(storageProps);
        ScratchWorkspace scratch = new ScratchWorkspace(storageProps);
        storage = new AigenStorageService(props, objectStorage, keys, scratch, storageProps);
    }

    @Test
    void allowsAssetsRelativePath() throws Exception {
        Path work = storage.ensureTaskDir("t1");
        Path p = storage.resolveAsset(work, "assets/audio/s1.mock.txt");
        assertTrue(p.startsWith(work.resolve("assets")));
    }

    @Test
    void rejectsTraversal() throws Exception {
        Path work = storage.ensureTaskDir("t2");
        assertThrows(BusinessException.class,
                () -> storage.resolveAsset(work, "assets/../secret.txt"));
        assertThrows(BusinessException.class,
                () -> storage.resolveAsset(work, "../../etc/passwd"));
        assertThrows(BusinessException.class,
                () -> storage.resolveAsset(work, "http://evil.com/a.mp3"));
    }
}
