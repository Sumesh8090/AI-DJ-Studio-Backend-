package com.djstudio.room;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The "Next Up" zone: a continuously re-ordered, shared queue.
 *
 * <p>Order (re-sorted after every mutation, so it is always a pure function of the data):
 * <ol>
 *   <li>manually promoted tracks first, the most recent promotion in front;</li>
 *   <li>then by vote count, highest first;</li>
 *   <li>then by the order tracks were added (stable and deterministic).</li>
 * </ol>
 * A user may upvote each queued track once. Not thread-safe by design: the owning {@link Room} is driven by a single
 * writer at a time, which is what makes every reorder atomic.
 */
public final class QueueState {

    public static final int MAX_SIZE = 50;
    public static final int POSITION_STEP = 1000;

    /** Immutable view of one slot, as sent to clients. position is sparse (1000, 2000, ...). */
    public record Slot(String trackId, String title, int durationSeconds, int voteCount, boolean pinned,
                       String addedBy, int position) {
    }

    static final class Entry {
        final String trackId;
        final String title;
        final int durationSeconds;
        final String addedBy;
        final long addSeq;
        final int baseVotes;                       // votes carried over from a persisted snapshot
        final Set<String> voters = new LinkedHashSet<>();
        long pinSeq = -1;

        Entry(String trackId, String title, int durationSeconds, String addedBy, long addSeq, int baseVotes) {
            this.trackId = trackId;
            this.title = title;
            this.durationSeconds = durationSeconds;
            this.addedBy = addedBy;
            this.addSeq = addSeq;
            this.baseVotes = baseVotes;
        }

        int voteCount() {
            return baseVotes + voters.size();
        }

        boolean pinned() {
            return pinSeq >= 0;
        }
    }

    /** The ordering rule, exposed so tests can assert that every broadcast snapshot obeys it. */
    static final Comparator<Entry> ORDER = Comparator
            .comparing((Entry e) -> !e.pinned())                       // pinned first
            .thenComparing((Entry a, Entry b) -> Long.compare(b.pinSeq, a.pinSeq))  // newest pin first
            .thenComparing((Entry a, Entry b) -> Integer.compare(b.voteCount(), a.voteCount()))
            .thenComparingLong(e -> e.addSeq);

    private final List<Entry> entries = new ArrayList<>();
    private long addCounter;
    private long pinCounter;

    public boolean contains(String trackId) {
        return find(trackId) != null;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int voteCount(String trackId) {
        Entry e = find(trackId);
        return e == null ? 0 : e.voteCount();
    }

    public void add(String trackId, String title, int durationSeconds, String addedBy, int baseVotes) {
        entries.add(new Entry(trackId, title, durationSeconds, addedBy, ++addCounter, Math.max(0, baseVotes)));
        reorder();
    }

    public boolean remove(String trackId) {
        boolean removed = entries.removeIf(e -> e.trackId.equals(trackId));
        if (removed) {
            reorder();
        }
        return removed;
    }

    /** Returns true if the vote changed anything (an upvote from the same user is idempotent). */
    public boolean vote(String trackId, String userId) {
        Entry e = find(trackId);
        if (e == null || !e.voters.add(userId)) {
            return false;
        }
        reorder();
        return true;
    }

    public boolean unvote(String trackId, String userId) {
        Entry e = find(trackId);
        if (e == null || !e.voters.remove(userId)) {
            return false;
        }
        reorder();
        return true;
    }

    public boolean promote(String trackId) {
        Entry e = find(trackId);
        if (e == null) {
            return false;
        }
        e.pinSeq = ++pinCounter;
        reorder();
        return true;
    }

    /** Removes and returns the head of the queue, or null if empty. */
    Entry pop() {
        return entries.isEmpty() ? null : entries.remove(0);
    }

    public List<String> trackIds() {
        List<String> ids = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            ids.add(e.trackId);
        }
        return ids;
    }

    public List<Slot> snapshot() {
        List<Slot> slots = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            slots.add(new Slot(e.trackId, e.title, e.durationSeconds, e.voteCount(), e.pinned(), e.addedBy,
                    (i + 1) * POSITION_STEP));
        }
        return slots;
    }

    private Entry find(String trackId) {
        for (Entry e : entries) {
            if (e.trackId.equals(trackId)) {
                return e;
            }
        }
        return null;
    }

    private void reorder() {
        entries.sort(ORDER);
    }
}
