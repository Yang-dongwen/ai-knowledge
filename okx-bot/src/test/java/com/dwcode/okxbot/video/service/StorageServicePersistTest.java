package com.dwcode.okxbot.video.service;

import com.dwcode.okxbot.storage.LocalObjectStorage;
import com.dwcode.okxbot.storage.ObjectKeyBuilder;
import com.dwcode.okxbot.storage.ScratchWorkspace;
import com.dwcode.okxbot.storage.config.StorageProperties;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.entity.VideoTaskEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR3：persist 后路径变为 object key，scratch 被清理。
 */
class StorageServicePersistTest {

    @TempDir
    Path temp;

    private StorageService storageService;
    private LocalObjectStorage objectStorage;

    @BeforeEach
    void setUp() {
        StorageProperties storageProps = new StorageProperties();
        storageProps.setEnvPrefix("dev");
        storageProps.getLocal().setRoot(temp.resolve("objects").toString());
        storageProps.getScratch().setRoot(temp.resolve("scratch").toString());
        storageProps.getCleanup().setScratchOnSuccess(true);
        storageProps.getCleanup().setScratchOnFailure(true);

        VideoProperties videoProps = new VideoProperties();
        videoProps.setWorkDir(temp.resolve("legacy-video").toString());

        objectStorage = new LocalObjectStorage(storageProps);
        ObjectKeyBuilder keys = new ObjectKeyBuilder(storageProps);
        ScratchWorkspace scratch = new ScratchWorkspace(storageProps);
        storageService = new StorageService(
                videoProps, new ObjectMapper(), objectStorage, keys, scratch, storageProps);
    }

    @Test
    void persistUploadsAndCleansScratch() throws Exception {
        VideoTaskEntity task = new VideoTaskEntity();
        task.setId(1001L);
        task.setUserId(7L);

        Path dir = storageService.ensureTaskDir("1001");
        Path video = dir.resolve("video.browser.mp4");
        Path audio = dir.resolve("audio.mp3");
        Files.writeString(video, "video-bytes");
        Files.writeString(audio, "audio-bytes");
        task.setVideoPath(video.toAbsolutePath().toString());
        task.setAudioPath(audio.toAbsolutePath().toString());

        storageService.persistAndCleanupAfterSuccess(task);

        assertEquals("dev/video/7/1001/video.browser.mp4", task.getVideoPath());
        assertEquals("dev/video/7/1001/audio.mp3", task.getAudioPath());
        assertTrue(objectStorage.exists(task.getVideoPath()));
        assertTrue(objectStorage.exists(task.getAudioPath()));
        assertTrue(storageService.mediaAvailable(task.getVideoPath()));
        // scratch 已清理
        assertFalse(Files.isDirectory(dir));
    }
}
