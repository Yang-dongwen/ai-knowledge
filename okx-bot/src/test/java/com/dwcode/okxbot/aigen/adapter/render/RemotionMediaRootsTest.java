package com.dwcode.okxbot.aigen.adapter.render;

import com.dwcode.okxbot.aigen.config.AigenProperties;
import com.dwcode.okxbot.storage.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RemotionMediaRootsTest {

    @TempDir
    Path temp;

    @Test
    void allowedRootCoversScratchAndLegacyAigen() {
        Path data = temp.resolve("data");
        AigenProperties aigen = new AigenProperties();
        aigen.setWorkDir(data.resolve("aigen").toString());
        StorageProperties storage = new StorageProperties();
        storage.getScratch().setRoot(data.resolve("_scratch").toString());

        RemotionMediaRoots roots = new RemotionMediaRoots(aigen, storage);
        Path allowed = roots.resolveAllowedWorkRoot();
        assertEquals(data.toAbsolutePath().normalize(), allowed);

        Path task = data.resolve("_scratch").resolve("aigen").resolve("123");
        assertEquals("_scratch/aigen/123", roots.mediaRelativeTaskPath(task));

        Path legacy = data.resolve("aigen").resolve("456");
        assertEquals("aigen/456", roots.mediaRelativeTaskPath(legacy));
    }

    @Test
    void commonAncestor() {
        Path data = Path.of("D:/gitprojects/auto-exchange/okx-bot/data").normalize();
        Path a = data.resolve("aigen");
        Path b = data.resolve("_scratch");
        assertEquals(data, RemotionMediaRoots.commonAncestor(a, b));
    }
}
