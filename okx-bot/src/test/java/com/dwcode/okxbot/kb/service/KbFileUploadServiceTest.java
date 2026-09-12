package com.dwcode.okxbot.kb.service;

import com.dwcode.okxbot.auth.entity.SysUserEntity;
import com.dwcode.okxbot.auth.security.AuthUserPrincipal;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.kb.config.KbProperties;
import com.dwcode.okxbot.kb.dto.FileResponse;
import com.dwcode.okxbot.kb.dto.FileUploadInitRequest;
import com.dwcode.okxbot.kb.entity.KbFileEntity;
import com.dwcode.okxbot.kb.entity.KbFileUploadEntity;
import com.dwcode.okxbot.kb.mapper.KbFileMapper;
import com.dwcode.okxbot.kb.mapper.KbFileUploadMapper;
import com.dwcode.okxbot.kb.mapper.KbNoteMapper;
import com.dwcode.okxbot.storage.LocalObjectStorage;
import com.dwcode.okxbot.storage.ObjectKeyBuilder;
import com.dwcode.okxbot.storage.ScratchWorkspace;
import com.dwcode.okxbot.storage.config.StorageProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KbFileUploadServiceTest {

    @TempDir
    Path temp;

    private final ConcurrentHashMap<Long, KbFileUploadEntity> uploads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, KbFileEntity> files = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1000);

    private KbFileUploadService service;
    private LocalObjectStorage storage;

    @BeforeEach
    void setUp() {
        login(1L);
        StorageProperties storageProperties = new StorageProperties();
        storageProperties.setEnvPrefix("dev");
        storageProperties.getLocal().setRoot(temp.resolve("objects").toString());
        storageProperties.getScratch().setRoot(temp.resolve("scratch").toString());
        storage = new LocalObjectStorage(storageProperties);
        ScratchWorkspace scratch = new ScratchWorkspace(storageProperties);
        ObjectKeyBuilder keys = new ObjectKeyBuilder(storageProperties);

        KbProperties kb = new KbProperties();
        kb.getFile().setMaxBytes(1024 * 1024);
        kb.getFile().setChunkSizeBytes(1024);
        kb.getFile().setMaxParts(64);
        kb.getFile().setMaxSessionsPerUser(3);
        kb.getFile().setMaxConcurrentPartsPerUser(4);
        kb.getFile().setMaxConcurrentPartsGlobal(8);
        kb.getFile().setMaxConcurrentCompleteGlobal(2);

        KbFileUploadMapper uploadMapper = mock(KbFileUploadMapper.class);
        KbFileMapper fileMapper = mock(KbFileMapper.class);
        KbNoteMapper noteMapper = mock(KbNoteMapper.class);
        when(noteMapper.selectById(anyLong())).thenReturn(null);

        when(uploadMapper.insert(any(KbFileUploadEntity.class))).thenAnswer(inv -> {
            KbFileUploadEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(ids.getAndIncrement());
            }
            uploads.put(e.getId(), e);
            return 1;
        });
        when(uploadMapper.selectById(any())).thenAnswer(inv -> uploads.get(inv.getArgument(0)));
        when(uploadMapper.updateById(any(KbFileUploadEntity.class))).thenAnswer(inv -> {
            KbFileUploadEntity e = inv.getArgument(0);
            uploads.put(e.getId(), e);
            return 1;
        });
        org.mockito.Mockito.doAnswer(inv -> {
            Object raw = inv.getArgument(0);
            if (raw instanceof Long id) {
                uploads.remove(id);
            }
            return 1;
        }).when(uploadMapper).deleteById(any(java.io.Serializable.class));
        when(uploadMapper.casStatus(any(), anyString(), anyString())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            String from = inv.getArgument(1);
            String to = inv.getArgument(2);
            KbFileUploadEntity e = uploads.get(id);
            if (e == null || !from.equals(e.getStatus())) {
                return 0;
            }
            e.setStatus(to);
            return 1;
        });

        when(fileMapper.insert(any(KbFileEntity.class))).thenAnswer(inv -> {
            KbFileEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(ids.getAndIncrement());
            }
            files.put(e.getId(), e);
            return 1;
        });
        when(fileMapper.selectById(any())).thenAnswer(inv -> files.get(inv.getArgument(0)));
        when(fileMapper.updateById(any(KbFileEntity.class))).thenAnswer(inv -> {
            KbFileEntity e = inv.getArgument(0);
            files.put(e.getId(), e);
            return 1;
        });
        org.mockito.Mockito.doAnswer(inv -> {
            Object raw = inv.getArgument(0);
            if (raw instanceof Long id) {
                files.remove(id);
            }
            return 1;
        }).when(fileMapper).deleteById(any(java.io.Serializable.class));

        service = new KbFileUploadService(
                uploadMapper, fileMapper, noteMapper, storage, keys, scratch, kb, new KbUploadLimiter(kb),
                org.mockito.Mockito.mock(com.dwcode.okxbot.rag.index.KbIndexOutboxService.class));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void chunkedUploadMergesAndStores() throws Exception {
        byte[] payload = twoPartPayload();
        var session = service.init(initReq("note.bin", payload.length, "application/octet-stream"));
        assertEquals(2, session.getTotalParts());
        assertEquals(1024, session.getChunkSize());

        service.putPart(session.getUploadId(), 2, new ByteArrayInputStream(payload, 1024, 1024), 1024L);
        service.putPart(session.getUploadId(), 1, new ByteArrayInputStream(payload, 0, 1024), 1024L);

        FileResponse file = service.complete(session.getUploadId());
        assertEquals(2048, file.getSizeBytes());
        assertEquals("note.bin", file.getOriginalName());
        assertTrue(storage.exists("dev/kb/1/" + file.getId() + "/note.bin"));
        try (var in = storage.openStream("dev/kb/1/" + file.getId() + "/note.bin")) {
            assertEquals(new String(payload, StandardCharsets.ISO_8859_1),
                    new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
        }
        FileResponse again = service.complete(session.getUploadId());
        assertEquals(file.getId(), again.getId());
    }

    @Test
    void concurrentPartsAreIdempotent() throws Exception {
        byte[] payload = twoPartPayload();
        var session = service.init(initReq("c.bin", payload.length, "application/octet-stream"));
        AuthUserPrincipal principal = SecurityUtils.requireCurrentUser();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);
        for (int i = 0; i < 2; i++) {
            Thread t1 = new Thread(() -> {
                await(start);
                try {
                    SecurityUtils.runAs(principal, () -> {
                        service.putPart(session.getUploadId(), 1,
                                new ByteArrayInputStream(payload, 0, 1024), 1024L);
                        return null;
                    });
                } finally {
                    done.countDown();
                }
            });
            Thread t2 = new Thread(() -> {
                await(start);
                try {
                    SecurityUtils.runAs(principal, () -> {
                        service.putPart(session.getUploadId(), 2,
                                new ByteArrayInputStream(payload, 1024, 1024), 1024L);
                        return null;
                    });
                } finally {
                    done.countDown();
                }
            });
            t1.start();
            t2.start();
        }
        start.countDown();
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));
        FileResponse file = service.complete(session.getUploadId());
        assertEquals(2048, file.getSizeBytes());
    }

    @Test
    void rejectOversizedInit() {
        FileUploadInitRequest req = initReq("big.bin", 2 * 1024 * 1024, "application/octet-stream");
        BusinessException ex = assertThrows(BusinessException.class, () -> service.init(req));
        assertEquals(400, ex.getCode());
    }

    @Test
    void allowsAnyExtensionIncludingHtml() {
        var session = service.init(initReq("x.html", 8, "text/html"));
        assertEquals("other", session.getKind());
        assertEquals("x.html", session.getOriginalName());
    }

    @Test
    void rejectWrongPartSize() {
        var session = service.init(initReq("a.bin", 2048, "application/octet-stream"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.putPart(session.getUploadId(), 1, new ByteArrayInputStream(new byte[3]), 3L));
        assertEquals(400, ex.getCode());
    }

    @Test
    void completeWithoutAllPartsFailsAndCanRetry() {
        byte[] payload = twoPartPayload();
        var session = service.init(initReq("n.bin", payload.length, "application/octet-stream"));
        service.putPart(session.getUploadId(), 1, new ByteArrayInputStream(payload, 0, 1024), 1024L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.complete(session.getUploadId()));
        assertEquals(400, ex.getCode());
        service.putPart(session.getUploadId(), 2, new ByteArrayInputStream(payload, 1024, 1024), 1024L);
        FileResponse file = service.complete(session.getUploadId());
        assertEquals(2048, file.getSizeBytes());
    }

    @Test
    void abortRemovesScratch() throws Exception {
        byte[] one = new byte[1024];
        java.util.Arrays.fill(one, (byte) 7);
        var session = service.init(initReq("z.bin", one.length, "application/octet-stream"));
        service.putPart(session.getUploadId(), 1, new ByteArrayInputStream(one), 1024L);
        Path part = temp.resolve("scratch").resolve("kb").resolve("up" + session.getUploadId()).resolve("part-1");
        assertTrue(Files.isRegularFile(part));
        service.abort(session.getUploadId());
        assertThrows(BusinessException.class, () -> service.get(session.getUploadId()));
        assertTrue(Files.notExists(part.getParent()) || !Files.isDirectory(part.getParent()));
    }

    @Test
    void sessionLimit() {
        KbProperties kb = new KbProperties();
        kb.getFile().setMaxSessionsPerUser(1);
        kb.getFile().setMaxConcurrentPartsGlobal(2);
        kb.getFile().setMaxConcurrentPartsPerUser(1);
        KbUploadLimiter limiter = new KbUploadLimiter(kb);
        limiter.acquireSession(1L);
        BusinessException ex = assertThrows(BusinessException.class, () -> limiter.acquireSession(1L));
        assertEquals(429, ex.getCode());
        limiter.releaseSession(1L);
        limiter.acquireSession(1L);
    }

    private static byte[] twoPartPayload() {
        byte[] payload = new byte[2048];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i & 0x7F);
        }
        return payload;
    }

    private static FileUploadInitRequest initReq(String name, long size, String ct) {
        FileUploadInitRequest req = new FileUploadInitRequest();
        req.setOriginalName(name);
        req.setSizeBytes(size);
        req.setContentType(ct);
        return req;
    }

    private static void login(long userId) {
        SysUserEntity u = new SysUserEntity();
        u.setId(userId);
        u.setEmail("u@t.t");
        u.setPasswordHash("x");
        u.setRole("USER");
        u.setStatus(1);
        u.setTokenVersion(0);
        AuthUserPrincipal p = new AuthUserPrincipal(u);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities()));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
