package com.djstudio.room;

import java.util.ArrayDeque;
import java.util.Deque;

/** Allows at most {@code max} events per {@code windowMs}. One instance per session per bucket. Not thread-safe. */
public final class SlidingWindowLimiter {

    private final int max;
    private final long windowMs;
    private final Deque<Long> stamps = new ArrayDeque<>();

    public SlidingWindowLimiter(int max, long windowMs) {
        this.max = max;
        this.windowMs = windowMs;
    }

    public boolean tryAcquire(long now) {
        while (!stamps.isEmpty() && now - stamps.peekFirst() >= windowMs) {
            stamps.pollFirst();
        }
        if (stamps.size() >= max) {
            return false;
        }
        stamps.addLast(now);
        return true;
    }
}
