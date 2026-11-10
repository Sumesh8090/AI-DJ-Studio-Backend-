package com.djstudio.room;

import java.util.Map;

/**
 * Side effects the room asks its host (the gateway) to perform. Keeping them as data means the room itself never
 * touches a database or a socket, so every rule can be unit-tested.
 */
public interface Effect {

    record CloseSessions(String userId, String reason) implements Effect {
    }

    record PersistLocked(boolean locked) implements Effect {
    }

    record PersistStatus(String status) implements Effect {
    }

    record PersistHost(String hostUserId, String previousHostUserId) implements Effect {
    }

    record PersistRole(String userId, String loungeRole) implements Effect {
    }

    record PersistMuted(String userId, boolean muted) implements Effect {
    }

    /** The participant was removed by the host; mark them as having left. */
    record PersistKick(String userId) implements Effect {
    }

    /** A chat message with its sentiment score, for the analytics dashboard (chat_messages). */
    record SaveChat(String userId, String body, double sentiment, int tsSeconds) implements Effect {
    }

    /** A listener finished (or left) a track: feeds the recommender (interactions). */
    record SaveInteraction(String userId, String trackId, int playedSeconds, boolean completed, boolean liked)
            implements Effect {
    }

    record BattleCandidate(String trackId, String submittedBy, int votes) {
    }

    /** A finished Soundclash (soundclash_battles + soundclash_candidates). winnerTrackId may be null. */
    record SaveBattle(long openedAtMs, long closesAtMs, String winnerTrackId, java.util.List<BattleCandidate> candidates)
            implements Effect {
    }

    /** One append-only log row (vote, advance, chat, moderation...). Batched by the writer, never per-event DB I/O. */
    record LogEvent(String type, String userId, Map<String, Object> payload) implements Effect {
    }
}
