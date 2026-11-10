package com.djstudio.service;

import com.djstudio.domain.DjLounge;
import com.djstudio.domain.QueueEntry;
import com.djstudio.repo.DjLoungeRepository;
import com.djstudio.repo.LoungeParticipantRepository;
import com.djstudio.repo.QueueEntryRepository;
import com.djstudio.room.Effect;
import com.djstudio.room.QueueState;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies the room's persistence effects. The room itself is authoritative; the database is its durable copy. */
@Service
public class LoungePersistence {

    private final DjLoungeRepository lounges;
    private final LoungeParticipantRepository participants;
    private final QueueEntryRepository queue;
    private final EventLogWriter eventLog;
    private final ActivityWriter activity;
    private final JdbcTemplate jdbc;

    public LoungePersistence(DjLoungeRepository lounges, LoungeParticipantRepository participants,
                             QueueEntryRepository queue, EventLogWriter eventLog, ActivityWriter activity,
                             JdbcTemplate jdbc) {
        this.lounges = lounges;
        this.participants = participants;
        this.queue = queue;
        this.eventLog = eventLog;
        this.activity = activity;
        this.jdbc = jdbc;
    }

    @Transactional
    public void apply(String loungeId, List<Effect> effects) {
        for (Effect e : effects) {
            if (e instanceof Effect.LogEvent l) {
                eventLog.enqueue(loungeId, l.type(), l.userId(), l.payload());
            } else if (e instanceof Effect.SaveChat c) {
                activity.chat(loungeId, c.userId(), c.body(), c.sentiment(), c.tsSeconds());
            } else if (e instanceof Effect.SaveInteraction i) {
                activity.interaction(i.userId(), i.trackId(), i.playedSeconds(), i.completed(), i.liked());
            } else if (e instanceof Effect.SaveBattle b) {
                saveBattle(loungeId, b);
            } else if (e instanceof Effect.PersistStatus s) {
                lounges.findById(loungeId).ifPresent(l -> {
                    if ("ended".equals(s.status())) {
                        l.markEnded();
                    } else {
                        l.setStatus(s.status());
                    }
                });
            } else if (e instanceof Effect.PersistLocked p) {
                lounges.findById(loungeId).ifPresent(l -> l.setLocked(p.locked()));
            } else if (e instanceof Effect.PersistHost h) {
                lounges.findById(loungeId).ifPresent(l -> l.setHostUserId(h.hostUserId()));
            } else if (e instanceof Effect.PersistRole r) {
                participants.findByLoungeIdAndUserId(loungeId, r.userId()).ifPresent(m -> m.setLoungeRole(r.loungeRole()));
            } else if (e instanceof Effect.PersistMuted m) {
                participants.findByLoungeIdAndUserId(loungeId, m.userId()).ifPresent(p -> p.setMuted(m.muted()));
            } else if (e instanceof Effect.PersistKick k) {
                participants.findByLoungeIdAndUserId(loungeId, k.userId()).ifPresent(p -> p.setLeftAt(Instant.now()));
            }
            // CloseSessions is handled by the gateway (it owns the sockets), not here.
        }
    }

    private void saveBattle(String loungeId, Effect.SaveBattle b) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO soundclash_battles "
                    + "(lounge_id, opened_at, closes_at, status, winning_track_id) VALUES (?, ?, ?, 'resolved', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, loungeId);
            ps.setTimestamp(2, Timestamp.from(Instant.ofEpochMilli(b.openedAtMs())));
            ps.setTimestamp(3, Timestamp.from(Instant.ofEpochMilli(b.closesAtMs())));
            ps.setString(4, b.winnerTrackId());
            return ps;
        }, keys);
        Number id = keys.getKey();
        if (id == null || b.candidates().isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO soundclash_candidates (battle_id, track_id, submitted_by_user_id, vote_count) "
                + "VALUES (?, ?, ?, ?)", b.candidates(), b.candidates().size(), (ps, c) -> {
            ps.setLong(1, id.longValue());
            ps.setString(2, c.trackId());
            ps.setString(3, c.submittedBy());
            ps.setInt(4, c.votes());
        });
    }

    /**
     * Replaces the persisted Next Up snapshot and the now-playing pointer. Called at most about once a second per
     * room (debounced by the ticker), from the room's single-thread effects executor, so writes never interleave.
     */
    @Transactional
    public void saveQueue(String loungeId, List<QueueState.Slot> slots, String nowPlayingTrackId) {
        DjLounge lounge = lounges.findById(loungeId).orElse(null);
        if (lounge == null) {
            return;
        }
        lounge.setCurrentTrackId(nowPlayingTrackId);
        queue.deleteByLoungeId(loungeId);
        queue.saveAll(slots.stream()
                .map(s -> new QueueEntry(loungeId, s.trackId(), s.position(), s.voteCount(), s.addedBy()))
                .toList());
    }
}
