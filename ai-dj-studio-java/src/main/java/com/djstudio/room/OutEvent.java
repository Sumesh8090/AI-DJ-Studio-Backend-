package com.djstudio.room;

import java.util.Map;

/**
 * One event the room wants delivered. toUserId == null means broadcast to everyone in the room.
 * seq is the room's monotonic sequence number, so clients can detect a gap and re-sync.
 */
public record OutEvent(String name, Map<String, Object> payload, String toUserId, long seq) {

    public boolean isBroadcast() {
        return toUserId == null;
    }
}
