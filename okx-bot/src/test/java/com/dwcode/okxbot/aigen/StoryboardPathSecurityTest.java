package com.dwcode.okxbot.aigen;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.aigen.service.AigenStorageService;
import com.dwcode.okxbot.common.exception.BusinessException;
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
        props.setWorkDir(temp.toString());
        storage = new AigenStorageService(props);
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
