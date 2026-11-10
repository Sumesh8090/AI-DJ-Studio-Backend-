package com.djstudio.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FailureLimiterTest {

    @Test
    void blocksAfterTheLimitAndReportsWhenToRetry() {
        FailureLimiter l = new FailureLimiter(3, 60_000, 100);
        for (int i = 0; i < 3; i++) {
            assertEquals(0, l.retryAfterSeconds("a", i * 1000L));
            l.recordFailure("a", i * 1000L);
        }
        assertEquals(58, l.retryAfterSeconds("a", 2_000));           // oldest failure (t=0) ages out at 60 s
        assertEquals(1, l.retryAfterSeconds("a", 59_999));
        assertEquals(0, l.retryAfterSeconds("a", 60_000));           // window passed: unblocked
    }

    @Test
    void keysAreIndependentAndSuccessClears() {
        FailureLimiter l = new FailureLimiter(2, 60_000, 100);
        l.recordFailure("a", 0);
        l.recordFailure("a", 1);
        assertTrue(l.retryAfterSeconds("a", 2) > 0);
        assertEquals(0, l.retryAfterSeconds("b", 2));
        l.clear("a");
        assertEquals(0, l.retryAfterSeconds("a", 3));
    }

    @Test
    void oldFailuresStopCounting() {
        FailureLimiter l = new FailureLimiter(2, 10_000, 100);
        l.recordFailure("a", 0);
        l.recordFailure("a", 20_000);                                  // the first one expired
        assertEquals(0, l.retryAfterSeconds("a", 20_001));
    }

    @Test
    void memoryIsBounded() {
        FailureLimiter l = new FailureLimiter(5, 60_000, 10);
        for (int i = 0; i < 1000; i++) {
            l.recordFailure("k" + i, i);
        }
        assertTrue(l.trackedKeys() <= 10);
    }

    @Test
    void rejectsNonsenseLimits() {
        assertThrows(IllegalArgumentException.class, () -> new FailureLimiter(0, 1, 1));
    }
}
