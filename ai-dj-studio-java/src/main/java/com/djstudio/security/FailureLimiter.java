package com.djstudio.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Counts failures per key inside a sliding window and blocks a key that reaches the limit until its oldest failure
 * ages out. Thread-safe, time is passed in (deterministic tests), and memory is bounded: when the table is full,
 * expired keys are purged first and, if still full, the least recently failed key is dropped.
 */
public final class FailureLimiter {

    private final int maxFailures;
    private final long windowMs;
    private final int maxKeys;
    private final Map<String, Deque<Long>> failures = new HashMap<>();

    public FailureLimiter(int maxFailures, long windowMs, int maxKeys) {
        if (maxFailures < 1 || windowMs < 1 || maxKeys < 1) {
            throw new IllegalArgumentException("limits must be positive");
        }
        this.maxFailures = maxFailures;
        this.windowMs = windowMs;
        this.maxKeys = maxKeys;
    }

    /** @return 0 if the key may proceed, otherwise the whole seconds until it may try again. */
    public synchronized long retryAfterSeconds(String key, long now) {
        Deque<Long> q = failures.get(key);
        if (q == null) {
            return 0;
        }
        trim(q, now);
        if (q.isEmpty()) {
            failures.remove(key);
            return 0;
        }
        if (q.size() < maxFailures) {
            return 0;
        }
        long waitMs = q.peekFirst() + windowMs - now;
        return Math.max(1, (waitMs + 999) / 1000);
    }

    public synchronized void recordFailure(String key, long now) {
        if (!failures.containsKey(key) && failures.size() >= maxKeys) {
            makeRoom(now);
        }
        Deque<Long> q = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        trim(q, now);
        q.addLast(now);
    }

    public synchronized void clear(String key) {
        failures.remove(key);
    }

    synchronized int trackedKeys() {
        return failures.size();
    }

    private void trim(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() >= windowMs) {
            q.pollFirst();
        }
    }

    private void makeRoom(long now) {
        Iterator<Map.Entry<String, Deque<Long>>> it = failures.entrySet().iterator();
        while (it.hasNext()) {
            Deque<Long> q = it.next().getValue();
            trim(q, now);
            if (q.isEmpty()) {
                it.remove();
            }
        }
        if (failures.size() >= maxKeys) {
            String oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (var e : failures.entrySet()) {
                long last = e.getValue().peekLast();
                if (last < oldestAt) {
                    oldestAt = last;
                    oldest = e.getKey();
                }
            }
            failures.remove(oldest);
        }
    }
}
