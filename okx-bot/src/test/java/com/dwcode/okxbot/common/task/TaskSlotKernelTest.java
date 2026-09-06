package com.dwcode.okxbot.common.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TaskSlotKernelTest {

    private TaskSlotKernel slots;

    @BeforeEach
    void setUp() {
        slots = new TaskSlotKernel("test");
    }

    @Test
    void freeSlotsUsesMaxOfDbAndActive() {
        slots.markRunning(1L);
        slots.markRunning(2L);
        assertEquals(0, slots.freeSlots(2, 0));
        assertEquals(0, slots.freeSlots(2, 5));
        assertEquals(1, slots.freeSlots(3, 0));
    }

    @Test
    void startPendingRespectsMaxConcurrent() {
        List<Long> pending = List.of(1L, 2L, 3L);
        List<Long> started = new ArrayList<>();
        int n = slots.startPending(2, 0, pending, id -> id, null, started::add);
        assertEquals(2, n);
        assertEquals(List.of(1L, 2L), started);
        assertTrue(slots.isActive(1L));
        assertTrue(slots.isActive(2L));
        assertFalse(slots.isActive(3L));
    }

    @Test
    void startPendingSkipsRejectedAndAlreadyActive() {
        slots.markRunning(1L);
        List<Long> pending = List.of(1L, 2L, 3L);
        List<Long> started = new ArrayList<>();
        slots.startPending(2, 0, pending, id -> id, id -> id != 2L, started::add);
        assertEquals(List.of(3L), started);
    }

    @Test
    void startPendingUnclaimsOnStartFailure() {
        List<Long> pending = List.of(8L, 9L);
        AtomicInteger calls = new AtomicInteger();
        int n = slots.startPending(2, 0, pending, id -> id, null, id -> {
            calls.incrementAndGet();
            if (id == 8L) {
                throw new IllegalStateException("boom");
            }
        });
        assertEquals(1, n);
        assertEquals(2, calls.get());
        assertFalse(slots.isActive(8L));
        assertTrue(slots.isActive(9L));
    }

    @Test
    void pauseAndCancelClearedOnRelease() {
        slots.requestPause(4L);
        slots.requestCancel(4L);
        assertTrue(slots.isPauseRequested(4L));
        assertTrue(slots.isCancelRequested(4L));
        slots.release(4L);
        assertFalse(slots.isPauseRequested(4L));
        assertFalse(slots.isCancelRequested(4L));
    }

    @Test
    void pendingFetchLimits() {
        assertEquals(4, TaskSlotKernel.pendingFetchLimit(1));
        assertEquals(8, TaskSlotKernel.pendingFetchLimit(4));
        assertEquals(8, TaskSlotKernel.pendingFetchLimitPerUser(1));
        assertEquals(16, TaskSlotKernel.pendingFetchLimitPerUser(4));
    }
}
