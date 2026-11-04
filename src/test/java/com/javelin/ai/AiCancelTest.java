package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AiCancelTest {
    @Test void hooksRunOnceWhenCancelled() {
        AiCancel cancel = new AiCancel();
        AtomicInteger runs = new AtomicInteger();
        cancel.onCancel(runs::incrementAndGet);
        assertFalse(cancel.isCancelled());
        cancel.cancel();
        cancel.cancel();
        assertTrue(cancel.isCancelled());
        assertEquals(1, runs.get());
    }

    @Test void aHookAddedAfterwardsRunsAtOnce() {
        AiCancel cancel = new AiCancel();
        cancel.cancel();
        AtomicInteger runs = new AtomicInteger();
        cancel.onCancel(runs::incrementAndGet);
        assertEquals(1, runs.get());
    }

    @Test void aWithdrawnHookDoesNotRun() {
        AiCancel cancel = new AiCancel();
        AtomicInteger runs = new AtomicInteger();
        cancel.onCancel(runs::incrementAndGet).run();
        cancel.cancel();
        assertEquals(0, runs.get());
    }

    @Test void throwIfCancelledReportsCancelled() throws Exception {
        AiCancel cancel = new AiCancel();
        cancel.throwIfCancelled();
        cancel.cancel();
        assertEquals(AiException.Kind.CANCELLED, assertThrows(AiException.class, cancel::throwIfCancelled).kind());
    }
}
