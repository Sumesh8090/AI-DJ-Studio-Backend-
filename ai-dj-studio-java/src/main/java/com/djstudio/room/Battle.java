package com.djstudio.room;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One Soundclash: participants each submit a candidate track, everyone votes (one vote each, changeable), and when
 * the window closes the most-voted track wins. Ties are broken deterministically by who submitted first.
 * Not thread-safe; owned by a {@link Room}.
 */
public final class Battle {

    public static final int MAX_CANDIDATES = 4;

    public record Candidate(String trackId, String title, int durationSeconds, String submittedBy, int votes) {
    }

    private static final class Cand {
        final String trackId;
        final String title;
        final int durationSeconds;
        final String submittedBy;
        final Set<String> voters = new LinkedHashSet<>();

        Cand(String trackId, String title, int durationSeconds, String submittedBy) {
            this.trackId = trackId;
            this.title = title;
            this.durationSeconds = durationSeconds;
            this.submittedBy = submittedBy;
        }
    }

    private final long id;
    private final long openedAt;
    private final long closesAt;
    private final Map<String, Cand> candidates = new LinkedHashMap<>();   // insertion order = submission order

    public Battle(long id, long openedAt, long closesAt) {
        this.id = id;
        this.openedAt = openedAt;
        this.closesAt = closesAt;
    }

    public long id() {
        return id;
    }

    public long openedAt() {
        return openedAt;
    }

    public long closesAt() {
        return closesAt;
    }

    public boolean isFull() {
        return candidates.size() >= MAX_CANDIDATES;
    }

    public boolean contains(String trackId) {
        return candidates.containsKey(trackId);
    }

    public boolean hasSubmitted(String userId) {
        return candidates.values().stream().anyMatch(c -> c.submittedBy.equals(userId));
    }

    public void submit(String userId, String trackId, String title, int durationSeconds) {
        candidates.put(trackId, new Cand(trackId, title, durationSeconds, userId));
    }

    /** Casts or moves the user's single vote. Returns false if nothing changed. */
    public boolean vote(String userId, String trackId) {
        Cand target = candidates.get(trackId);
        if (target == null || target.voters.contains(userId)) {
            return false;
        }
        for (Cand c : candidates.values()) {
            c.voters.remove(userId);
        }
        target.voters.add(userId);
        return true;
    }

    public int totalVotes() {
        return candidates.values().stream().mapToInt(c -> c.voters.size()).sum();
    }

    public List<Candidate> tally() {
        List<Candidate> out = new ArrayList<>();
        for (Cand c : candidates.values()) {
            out.add(new Candidate(c.trackId, c.title, c.durationSeconds, c.submittedBy, c.voters.size()));
        }
        return out;
    }

    /** Most votes; ties go to the earliest submission. Null when there are no candidates. */
    public Candidate leader() {
        Candidate best = null;
        for (Candidate c : tally()) {
            if (best == null || c.votes() > best.votes()) {
                best = c;
            }
        }
        return best;
    }

    /** True when the leader shares the top vote count with another candidate. */
    public boolean isTied() {
        Candidate lead = leader();
        if (lead == null) {
            return false;
        }
        return tally().stream().filter(c -> c.votes() == lead.votes()).count() > 1;
    }
}
