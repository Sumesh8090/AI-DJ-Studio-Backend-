package com.djstudio.room;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Who is connected, with a reconnect grace window.
 *
 * <p>A user may have several connections (tabs). When the last one drops they are not "gone" immediately: they enter
 * a grace window, and a reconnect inside it resumes silently, with no duplicate "joined" event. Only when the grace
 * period expires is the user reported as having left. Time is passed in, so behaviour is deterministic in tests.
 */
public final class PresenceTracker {

    public enum ConnectKind {
        /** First connection (or return after the grace expired): announce "joined". */
        NEW,
        /** Reconnect within the grace window: resume silently. */
        RECONNECT,
        /** Another tab/device for a user who is already online. */
        ADDITIONAL
    }

    private final long graceMs;
    private final Map<String, Integer> connections = new HashMap<>();
    private final Map<String, Long> graceUntil = new HashMap<>();

    public PresenceTracker(long graceMs) {
        this.graceMs = graceMs;
    }

    public ConnectKind connect(String userId, long now) {
        int open = connections.getOrDefault(userId, 0);
        connections.put(userId, open + 1);
        if (open > 0) {
            return ConnectKind.ADDITIONAL;
        }
        return graceUntil.remove(userId) != null ? ConnectKind.RECONNECT : ConnectKind.NEW;
    }

    /** Returns true when this was the user's last connection (grace window started). */
    public boolean disconnect(String userId, long now) {
        int open = connections.getOrDefault(userId, 0);
        if (open <= 0) {
            return false;
        }
        if (open > 1) {
            connections.put(userId, open - 1);
            return false;
        }
        connections.remove(userId);
        graceUntil.put(userId, now + graceMs);
        return true;
    }

    /** Users whose grace window has ended. Each is reported exactly once. */
    public List<String> expire(long now) {
        List<String> expired = new ArrayList<>();
        Iterator<Map.Entry<String, Long>> it = graceUntil.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() <= now) {
                expired.add(e.getKey());
                it.remove();
            }
        }
        expired.sort(String::compareTo); // deterministic order
        return expired;
    }

    public boolean isOnline(String userId) {
        return connections.getOrDefault(userId, 0) > 0;
    }

    public int onlineCount() {
        return connections.size();
    }

    public int connectionCount(String userId) {
        return connections.getOrDefault(userId, 0);
    }

    /** Forget a user entirely (kicked). */
    public void drop(String userId) {
        connections.remove(userId);
        graceUntil.remove(userId);
    }
}
