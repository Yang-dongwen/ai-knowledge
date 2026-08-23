package com.dwcode.okxbot.kb.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbNoteRevisionPolicyTest {

    @Test
    void requestedAlwaysWrites() {
        assertTrue(KbNoteService.shouldWriteRevision(true, LocalDateTime.now(), 5));
    }

    @Test
    void firstAutosaveWrites() {
        assertTrue(KbNoteService.shouldWriteRevision(false, null, 5));
    }

    @Test
    void recentAutosaveSkipped() {
        assertFalse(KbNoteService.shouldWriteRevision(false, LocalDateTime.now().minusMinutes(1), 5));
    }

    @Test
    void staleAutosaveWrites() {
        assertTrue(KbNoteService.shouldWriteRevision(false, LocalDateTime.now().minusMinutes(6), 5));
    }
}
