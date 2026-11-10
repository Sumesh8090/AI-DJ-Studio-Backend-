package com.djstudio.room;

import com.djstudio.core.Permission;
import com.djstudio.core.Role;
import com.djstudio.core.Sanitizer;
import com.djstudio.sentiment.SentimentAggregator;
import com.djstudio.sentiment.SentimentClassifier;
import java.util.HashSet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The authoritative, framework-free state machine of one lounge. It owns the queue, presence, host policy,
 * moderation and the AI-suggestion lifecycle. Every public method takes the current time and returns a
 * {@link Result} (events to deliver + effects to perform); it never touches sockets or the database.
 *
 * <p><b>Concurrency:</b> not thread-safe. The gateway serialises all calls for one room (single writer), so each
 * mutation produces exactly one consistent broadcast and every client sees the same sequence of states.
 *
 * <p><b>Atomic reorders:</b> every {@code lounge.queue.update} carries the full queue plus now-playing, never a
 * diff, so a client can never apply a half-finished reorder.
 *
 * <p><b>Host-disconnect policy (deterministic):</b> once the host's reconnect grace expires, the earliest-joined
 * online co-DJ becomes acting host; with no co-DJ online the room is FROZEN: user queue changes are rejected with
 * {@code room_frozen} (chat and automatic playback progression continue) until the host or a co-DJ returns.
 */
public final class Room {

    public record Config(double bpmTolerancePct, long graceMs, int outroWindowSeconds, long autoApproveMs,
                         boolean aiEnabled, boolean soundclashEnabled, long battleWindowMs) {

        public Config(double bpmTolerancePct, long graceMs, int outroWindowSeconds, long autoApproveMs,
                      boolean aiEnabled) {
            this(bpmTolerancePct, graceMs, outroWindowSeconds, autoApproveMs, aiEnabled, true, 30_000);
        }

        public static Config defaults() {
            return new Config(8.0, 15_000, 20, 10_000, true);
        }
    }

    public record TrackRef(String trackId, String title, int durationSeconds) {
    }

    public enum HostState { PRESENT, ACTING_HOST, FROZEN }

    /** A pending AI proposal. selectedTrackId is always a track that is currently in the queue. */
    public record Suggestion(String selectedTrackId, String transitionScript, Map<String, Object> matchReason,
                             List<String> sourceEvents, long createdAt, long autoApproveAt) {
    }

    /** Everything the AI DJ needs to decide the next segue. */
    public record AiContext(String currentTrackId, List<String> candidateTrackIds, List<String> recentTrackIds,
                            double sentiment, double sentimentTrend) {
    }

    static final Set<String> REACTIONS = Set.of("fire", "heart", "clap", "dance", "wow", "sleep", "thumbsdown");
    private static final Map<String, Double> REACTION_VALUE = Map.of(
            "fire", 0.9, "heart", 0.8, "clap", 0.7, "dance", 0.9, "wow", 0.7, "sleep", 0.15, "thumbsdown", 0.1);
    private static final int CHAT_MAX = 500;
    private static final int RECENT_MAX = 10;

    private static final class Member {
        final String userId;
        final String name;
        final Role globalRole;
        final long joinSeq;
        Role loungeRole;
        boolean muted;
        long rttMs = -1;

        Member(String userId, String name, Role globalRole, Role loungeRole, boolean muted, long joinSeq) {
            this.userId = userId;
            this.name = name;
            this.globalRole = globalRole;
            this.loungeRole = loungeRole;
            this.muted = muted;
            this.joinSeq = joinSeq;
        }
    }

    private final String loungeId;
    private final Config config;
    private final PresenceTracker presence;
    private final QueueState queue = new QueueState();
    private final Map<String, Member> members = new LinkedHashMap<>();
    private final Deque<String> recent = new ArrayDeque<>();

    private String ownerUserId;
    private String actingHostId;
    private boolean hostAway;
    private String status = "scheduled";
    private boolean locked;
    private long seq;
    private long joinCounter;
    private long chatCounter;

    private TrackRef nowPlaying;
    private long nowPlayingStartedAt;
    private boolean segueRequestedForCurrent;
    private Suggestion suggestion;
    private String approvedScriptTrackId;
    private String approvedScript;

    private final SentimentAggregator mood = SentimentAggregator.standard();
    private final SentimentClassifier classifier = new SentimentClassifier();
    private final Set<String> presentThisTrack = new HashSet<>();
    private final Set<String> likedThisTrack = new HashSet<>();
    private Battle battle;
    private int peakOnline;
    private long battleCounter;
    private boolean dirty;

    public Room(String loungeId, String ownerUserId, Config config) {
        this.loungeId = loungeId;
        this.ownerUserId = ownerUserId;
        this.config = config;
        this.presence = new PresenceTracker(config.graceMs());
    }

    // ------------------------------------------------------------------ loading (no events)

    /** Registers a persisted participant. Used when a room is (re)built from the database. */
    public void addMember(String userId, String name, Role globalRole, Role loungeRole, boolean muted) {
        members.putIfAbsent(userId, new Member(userId, name, globalRole, loungeRole, muted, ++joinCounter));
    }

    public void restoreStatus(String status, boolean locked) {
        this.status = status;
        this.locked = locked;
    }

    public void restoreQueue(String trackId, String title, int durationSeconds, String addedBy, int votes) {
        queue.add(trackId, title, durationSeconds, addedBy, votes);
    }

    public void restoreNowPlaying(TrackRef track, long startedAt) {
        this.nowPlaying = track;
        this.nowPlayingStartedAt = startedAt;
    }

    // ------------------------------------------------------------------ queries

    public String loungeId() {
        return loungeId;
    }

    public Config config() {
        return config;
    }

    public String status() {
        return status;
    }

    public boolean isLocked() {
        return locked;
    }

    public long seq() {
        return seq;
    }

    public boolean isMember(String userId) {
        return members.containsKey(userId);
    }

    public boolean isOnline(String userId) {
        return presence.isOnline(userId);
    }

    public int onlineCount() {
        return presence.onlineCount();
    }

    public int peakOnline() {
        return peakOnline;
    }

    public boolean isIdle() {
        return presence.onlineCount() == 0;
    }

    public HostState hostState() {
        if (!hostAway) {
            return HostState.PRESENT;
        }
        return actingHostId != null ? HostState.ACTING_HOST : HostState.FROZEN;
    }

    public String ownerUserId() {
        return ownerUserId;
    }

    public String actingHostId() {
        return actingHostId;
    }

    public List<String> queueTrackIds() {
        return queue.trackIds();
    }

    public List<QueueState.Slot> queueSlots() {
        return queue.snapshot();
    }

    public TrackRef nowPlaying() {
        return nowPlaying;
    }

    public Suggestion suggestion() {
        return suggestion;
    }

    public double sentiment(long now) {
        return mood.current(now);
    }

    /** True once if queue/now-playing changed since the last call. The gateway uses it to debounce persistence. */
    public boolean consumeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    public AiContext aiContext(long now) {
        return new AiContext(nowPlaying == null ? null : nowPlaying.trackId(), queue.trackIds(),
                new ArrayList<>(recent), mood.current(now), mood.trend(now));
    }

    /** Effective role inside this room (global admin > acting host > lounge role). Null when not a member. */
    public Role effectiveRole(String userId) {
        Member m = members.get(userId);
        if (m == null) {
            return null;
        }
        if (m.globalRole == Role.ADMIN) {
            return Role.ADMIN;
        }
        if (userId.equals(actingHostId)) {
            return Role.HOST;
        }
        return m.loungeRole;
    }

    // ------------------------------------------------------------------ connection lifecycle

    public Result connect(String userId, long now) {
        Member m = members.get(userId);
        if (m == null) {
            throw new RoomException("not_a_member", "Join the lounge before connecting.");
        }
        Result res = new Result();
        PresenceTracker.ConnectKind kind = presence.connect(userId, now);
        if (nowPlaying != null) {
            presentThisTrack.add(userId);
        }
        if (presence.onlineCount() > peakOnline) {
            peakOnline = presence.onlineCount();
            res.log("presence.peak", userId, Map.of("count", peakOnline));
        }

        if (userId.equals(ownerUserId) && hostAway) {
            hostAway = false;
            actingHostId = null;
            res.events().add(broadcast("lounge.host.changed", hostPayload(), res));
        } else if (hostAway && actingHostId == null && effectiveRole(userId) != null
                && m.loungeRole == Role.CO_DJ) {
            recomputeHost(res);
        }
        if (kind == PresenceTracker.ConnectKind.NEW) {
            res.events().add(broadcast("lounge.presence.update", presencePayload(userId, "joined"), res));
        }
        res.events().add(direct(userId, "lounge.state.snapshot", snapshot(now)));
        return res;
    }

    public Result disconnect(String userId, long now) {
        presence.disconnect(userId, now);
        return new Result(); // no event yet: the user is inside the reconnect grace window
    }

    // ------------------------------------------------------------------ clock

    public Result tick(long now) {
        Result res = new Result();
        for (String gone : presence.expire(now)) {
            res.events().add(broadcast("lounge.presence.update", presencePayload(gone, "left"), res));
            if (gone.equals(ownerUserId)) {
                hostAway = true;
                recomputeHost(res);
            } else if (hostAway && gone.equals(actingHostId)) {
                recomputeHost(res);
            }
        }
        if (suggestion != null && now >= suggestion.autoApproveAt()) {
            applySuggestion("auto", now, res);
        }
        if (battle != null && now >= battle.closesAt()) {
            resolveBattleInternal(now, false, res);
        }
        if ("live".equals(status) && nowPlaying != null) {
            long endAt = nowPlayingStartedAt + nowPlaying.durationSeconds() * 1000L;
            if (now >= endAt) {
                advanceInternal(now, "track_ended", res);
            } else if (config.aiEnabled() && !segueRequestedForCurrent && suggestion == null && !queue.isEmpty()
                    && endAt - now <= config.outroWindowSeconds() * 1000L) {
                segueRequestedForCurrent = true;
                res.requestSegue();
            }
        }
        return res;
    }

    // ------------------------------------------------------------------ queue

    public Result add(String userId, TrackRef track, long now) {
        requireMember(userId);
        requireUnfrozen();
        requireNotEnded();
        if (locked && !can(userId, Permission.OVERRIDE_QUEUE)) {
            throw new RoomException("lounge_locked", "The host has locked the queue.");
        }
        if (queue.contains(track.trackId()) || (nowPlaying != null && nowPlaying.trackId().equals(track.trackId()))) {
            throw new RoomException("duplicate_track", "That track is already queued or playing.");
        }
        if (queue.size() >= QueueState.MAX_SIZE) {
            throw new RoomException("queue_full", "The queue is full (" + QueueState.MAX_SIZE + ").");
        }
        Result res = new Result();
        queue.add(track.trackId(), track.title(), track.durationSeconds(), userId, 0);
        res.log("queue.add", userId, Map.of("trackId", track.trackId()));
        if ("live".equals(status) && nowPlaying == null) {
            advanceInternal(now, "queue_started", res);
        } else {
            queueChanged("add", res);
        }
        return res;
    }

    public Result remove(String userId, String trackId, long now) {
        requireMember(userId);
        requireUnfrozen();
        Member m = members.get(userId);
        boolean mayRemove = can(userId, Permission.OVERRIDE_QUEUE);
        if (!mayRemove) {
            throw new RoomException("forbidden", "Only a co-DJ or host can remove tracks.");
        }
        if (!queue.remove(trackId)) {
            throw new RoomException("not_found", "That track is not in the queue.");
        }
        Result res = new Result();
        dropSuggestionIf(trackId, res);
        res.log("queue.remove", m.userId, Map.of("trackId", trackId));
        queueChanged("remove", res);
        return res;
    }

    public Result vote(String userId, String trackId, long now) {
        requireMember(userId);
        requireUnfrozen();
        requireNotEnded();
        if (!queue.contains(trackId)) {
            throw new RoomException("not_found", "That track is not in the queue.");
        }
        Result res = new Result();
        if (queue.vote(trackId, userId)) {
            res.log("queue.vote", userId, Map.of("trackId", trackId));
            queueChanged("vote", res);
        }
        return res;
    }

    public Result unvote(String userId, String trackId, long now) {
        requireMember(userId);
        requireUnfrozen();
        Result res = new Result();
        if (queue.unvote(trackId, userId)) {
            res.log("queue.unvote", userId, Map.of("trackId", trackId));
            queueChanged("unvote", res);
        }
        return res;
    }

    public Result promote(String userId, String trackId, long now) {
        requirePermission(userId, Permission.OVERRIDE_QUEUE);
        requireUnfrozen();
        if (!queue.promote(trackId)) {
            throw new RoomException("not_found", "That track is not in the queue.");
        }
        Result res = new Result();
        res.log("queue.promote", userId, Map.of("trackId", trackId));
        queueChanged("promote", res);
        return res;
    }

    public Result advance(String userId, long now) {
        requirePermission(userId, Permission.OVERRIDE_QUEUE);
        requireUnfrozen();
        requireNotEnded();
        Result res = new Result();
        advanceInternal(now, "manual_advance", res);
        return res;
    }

    public Result start(String userId, long now) {
        requirePermission(userId, Permission.MANAGE_LOUNGE);
        if ("ended".equals(status)) {
            throw new RoomException("lounge_ended", "This lounge has ended.");
        }
        Result res = new Result();
        if (!"live".equals(status)) {
            status = "live";
            res.log("lounge.start", userId, Map.of());
            res.effects().add(new Effect.PersistStatus("live"));
            res.events().add(broadcast("lounge.status", Map.of("status", "live"), res));
        }
        if (nowPlaying == null) {
            advanceInternal(now, "session_started", res);
        } else {
            nowPlayingStartedAt = now;      // a seed track restored from the database starts playing now
            startTrackAudience();
            queueChanged("session_started", res);
        }
        return res;
    }

    /** A co-DJ or host asks the AI DJ for a segue suggestion right now (instead of waiting for the outro window). */
    public Result requestSegue(String userId) {
        requirePermission(userId, Permission.OVERRIDE_QUEUE);
        requireUnfrozen();
        if (!"live".equals(status) || nowPlaying == null) {
            throw new RoomException("not_live", "A segue can only be suggested while a track is playing.");
        }
        if (queue.isEmpty()) {
            throw new RoomException("empty_queue", "Add tracks to Next Up first.");
        }
        Result res = new Result();
        res.requestSegue();
        return res;
    }

    public Result end(String userId, long now) {
        requirePermission(userId, Permission.MANAGE_LOUNGE);
        Result res = new Result();
        if (!"ended".equals(status)) {
            status = "ended";
            res.log("lounge.end", userId, Map.of());
            closeTrackInteractions(now, false, res);
            nowPlaying = null;
            suggestion = null;
            battle = null;
            res.effects().add(new Effect.PersistStatus("ended"));
            res.events().add(broadcast("lounge.status", Map.of("status", "ended"), res));
            queueChanged("ended", res);
        }
        return res;
    }

    // ------------------------------------------------------------------ AI segue lifecycle

    public Result setSuggestion(String selectedTrackId, String script, Map<String, Object> matchReason,
                                List<String> sourceEvents, long now) {
        Result res = new Result();
        if (!queue.contains(selectedTrackId) || !"live".equals(status)) {
            return res; // stale proposal: the queue moved on while the AI was thinking
        }
        suggestion = new Suggestion(selectedTrackId, script, matchReason, List.copyOf(sourceEvents), now,
                now + config.autoApproveMs());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("selectedTrackId", selectedTrackId);
        p.put("transitionScript", script);
        p.put("matchReason", matchReason);
        p.put("sourceEvents", sourceEvents);
        p.put("autoApproveAt", suggestion.autoApproveAt());
        res.events().add(broadcast("lounge.ai.segue.suggested", p, res));
        return res;
    }

    public Result approveSuggestion(String userId, long now) {
        requirePermission(userId, Permission.OVERRIDE_QUEUE);
        requireUnfrozen();
        if (suggestion == null) {
            throw new RoomException("no_suggestion", "There is no pending AI suggestion.");
        }
        Result res = new Result();
        applySuggestion(userId, now, res);
        return res;
    }

    public Result rejectSuggestion(String userId, long now) {
        requirePermission(userId, Permission.OVERRIDE_QUEUE);
        if (suggestion == null) {
            throw new RoomException("no_suggestion", "There is no pending AI suggestion.");
        }
        Result res = new Result();
        res.log("ai.segue.reject", userId, Map.of("trackId", suggestion.selectedTrackId()));
        suggestion = null;
        res.events().add(broadcast("lounge.ai.segue.rejected", Map.of("by", userId), res));
        return res;
    }

    private void applySuggestion(String by, long now, Result res) {
        Suggestion s = suggestion;
        suggestion = null;
        if (s == null || !queue.promote(s.selectedTrackId())) {
            return;
        }
        approvedScriptTrackId = s.selectedTrackId();
        approvedScript = s.transitionScript();
        res.log("ai.segue.approve", by, Map.of("trackId", s.selectedTrackId()));
        queueChanged("ai_segue", res);
    }

    private void dropSuggestionIf(String trackId, Result res) {
        if (suggestion != null && suggestion.selectedTrackId().equals(trackId)) {
            suggestion = null;
            res.events().add(broadcast("lounge.ai.segue.rejected", Map.of("by", "system"), res));
        }
    }

    // ------------------------------------------------------------------ chat & reactions

    public Result chat(String userId, String rawText, long now) {
        Member m = requireMember(userId);
        if (m.muted) {
            throw new RoomException("muted", "You have been muted by the host.");
        }
        String text;
        try {
            text = Sanitizer.sanitize(rawText, CHAT_MAX, "text");
        } catch (IllegalArgumentException e) {
            throw new RoomException("invalid_message", e.getMessage());
        }
        Result res = new Result();
        double score = classifier.classify(rawText).score();      // raw text: HTML escaping would break "don't"
        mood.add(now, score);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("messageId", "msg_" + (++chatCounter));
        p.put("userId", userId);
        p.put("name", m.name);
        p.put("text", text);
        p.put("sentiment", round3(score));
        p.put("sentAt", now);
        res.events().add(broadcast("lounge.chat.message", p, res));
        res.events().add(broadcast("lounge.sentiment.score",
                Map.of("score", round3(mood.current(now)), "trend", round3(mood.trend(now))), res));
        res.effects().add(new Effect.SaveChat(userId, text, score, trackSeconds(now)));
        res.log("chat", userId, Map.of("messageId", p.get("messageId")));
        return res;
    }

    public Result typing(String userId, boolean typing) {
        Member m = requireMember(userId);
        Result res = new Result();
        res.events().add(broadcast("lounge.chat.typing", Map.of("userId", userId, "name", m.name, "typing", typing),
                res));
        return res;
    }

    public Result react(String userId, String reaction, long now) {
        requireMember(userId);
        if (reaction == null || !REACTIONS.contains(reaction)) {
            throw new RoomException("invalid_reaction", "Unknown reaction.");
        }
        double value = REACTION_VALUE.get(reaction);
        mood.add(now, value);
        if (value >= 0.7 && nowPlaying != null) {
            likedThisTrack.add(userId);
        }
        Result res = new Result();
        res.events().add(broadcast("lounge.chat.reaction", Map.of("userId", userId, "reaction", reaction), res));
        res.events().add(broadcast("lounge.sentiment.score",
                Map.of("score", round3(mood.current(now)), "trend", round3(mood.trend(now))), res));
        return res;
    }

    // ------------------------------------------------------------------ moderation

    public Result setLocked(String userId, boolean value) {
        requirePermission(userId, Permission.MANAGE_LOUNGE);
        Result res = new Result();
        if (locked != value) {
            locked = value;
            res.effects().add(new Effect.PersistLocked(value));
            res.log("mod.lock", userId, Map.of("locked", value));
            res.events().add(broadcast("lounge.status", Map.of("status", status, "locked", value), res));
        }
        return res;
    }

    public Result mute(String userId, String targetId, boolean muted) {
        requirePermission(userId, Permission.MANAGE_LOUNGE);
        Member t = requireTarget(userId, targetId);
        Result res = new Result();
        t.muted = muted;
        res.effects().add(new Effect.PersistMuted(targetId, muted));
        res.log("mod.mute", userId, Map.of("target", targetId, "muted", muted));
        res.events().add(broadcast("lounge.member.muted", Map.of("userId", targetId, "muted", muted), res));
        return res;
    }

    public Result kick(String userId, String targetId, long now) {
        requirePermission(userId, Permission.MANAGE_LOUNGE);
        requireTarget(userId, targetId);
        Result res = new Result();
        members.remove(targetId);
        presence.drop(targetId);
        res.effects().add(new Effect.PersistKick(targetId));
        res.effects().add(new Effect.CloseSessions(targetId, "removed_by_host"));
        res.log("mod.kick", userId, Map.of("target", targetId));
        res.events().add(broadcast("lounge.presence.update", presencePayload(targetId, "removed"), res));
        if (targetId.equals(actingHostId)) {
            recomputeHost(res);
        }
        return res;
    }

    public Result setRole(String userId, String targetId, Role newRole, long now) {
        requirePermission(userId, Permission.MANAGE_LOUNGE);
        if (newRole != Role.LISTENER && newRole != Role.CO_DJ && newRole != Role.HOST) {
            throw new RoomException("invalid_role", "A lounge role must be listener, co_dj or host.");
        }
        Member t = members.get(targetId);
        if (t == null) {
            throw new RoomException("not_found", "That participant is not in the lounge.");
        }
        if (targetId.equals(ownerUserId) && newRole != Role.HOST) {
            throw new RoomException("forbidden", "Transfer the host role before changing the host's own role.");
        }
        Result res = new Result();
        if (newRole == Role.HOST) {
            if (!userId.equals(ownerUserId) && members.get(userId).globalRole != Role.ADMIN) {
                throw new RoomException("forbidden", "Only the host can transfer the host role.");
            }
            if (targetId.equals(ownerUserId)) {
                return res;
            }
            String previous = ownerUserId;
            Member old = members.get(previous);
            if (old != null) {
                old.loungeRole = Role.CO_DJ;
                res.effects().add(new Effect.PersistRole(previous, Role.CO_DJ.value()));
            }
            t.loungeRole = Role.HOST;
            ownerUserId = targetId;
            res.effects().add(new Effect.PersistHost(targetId, previous));
            res.effects().add(new Effect.PersistRole(targetId, Role.HOST.value()));
            hostAway = !presence.isOnline(targetId);
            actingHostId = null;
            if (hostAway) {
                recomputeHostQuiet();
            }
            res.log("mod.transfer_host", userId, Map.of("target", targetId));
            res.events().add(broadcast("lounge.host.changed", hostPayload(), res));
            return res;
        }
        t.loungeRole = newRole;
        res.effects().add(new Effect.PersistRole(targetId, newRole.value()));
        res.log("mod.set_role", userId, Map.of("target", targetId, "role", newRole.value()));
        res.events().add(broadcast("lounge.member.role", Map.of("userId", targetId, "role", newRole.value()), res));
        return res;
    }

    // ------------------------------------------------------------------ soundclash

    /** Co-DJ or host opens a Soundclash: everyone may submit one compatible track and vote until it closes. */
    public Result openBattle(String userId, long now) {
        requirePermission(userId, Permission.RESOLVE_BATTLE);
        requireUnfrozen();
        if (!config.soundclashEnabled()) {
            throw new RoomException("soundclash_disabled", "Soundclash is turned off for this lounge.");
        }
        if (!"live".equals(status) || nowPlaying == null) {
            throw new RoomException("not_live", "A Soundclash can only start while a track is playing.");
        }
        if (battle != null) {
            throw new RoomException("battle_open", "A Soundclash is already running.");
        }
        battle = new Battle(++battleCounter, now, now + config.battleWindowMs());
        Result res = new Result();
        res.log("soundclash.open", userId, Map.of("battleId", battle.id()));
        res.events().add(broadcast("lounge.soundclash.open", battlePayload(), res));
        return res;
    }

    /**
     * Submit a candidate. {@code compatible} is decided by the gateway (BPM and Camelot rules against the playing
     * track), so an incompatible track can never enter a battle.
     */
    public Result submitBattle(String userId, TrackRef track, boolean compatible, long now) {
        requireMember(userId);
        Battle b = requireOpenBattle(now);
        if (b.hasSubmitted(userId)) {
            throw new RoomException("already_submitted", "You already submitted a track to this Soundclash.");
        }
        if (b.contains(track.trackId()) || (nowPlaying != null && nowPlaying.trackId().equals(track.trackId()))) {
            throw new RoomException("duplicate_track", "That track is already in the Soundclash or playing.");
        }
        if (!compatible) {
            throw new RoomException("incompatible_segue",
                    "That track breaks the BPM/key rules for a segue from the current track.");
        }
        if (b.isFull()) {
            throw new RoomException("battle_full", "This Soundclash already has " + Battle.MAX_CANDIDATES + " tracks.");
        }
        b.submit(userId, track.trackId(), track.title(), track.durationSeconds());
        Result res = new Result();
        res.log("soundclash.submit", userId, Map.of("trackId", track.trackId()));
        res.events().add(broadcast("lounge.soundclash.update", battlePayload(), res));
        return res;
    }

    public Result voteBattle(String userId, String trackId, long now) {
        requireMember(userId);
        Battle b = requireOpenBattle(now);
        if (!b.contains(trackId)) {
            throw new RoomException("not_found", "That track is not in the Soundclash.");
        }
        Result res = new Result();
        if (b.vote(userId, trackId)) {
            res.log("soundclash.vote", userId, Map.of("trackId", trackId));
            res.events().add(broadcast("lounge.soundclash.update", battlePayload(), res));
        }
        return res;
    }

    /** Co-DJ or host closes the Soundclash early; the current leader wins (ties: earliest submission). */
    public Result resolveBattle(String userId, long now) {
        requirePermission(userId, Permission.RESOLVE_BATTLE);
        if (battle == null) {
            throw new RoomException("no_battle", "There is no running Soundclash.");
        }
        Result res = new Result();
        resolveBattleInternal(now, true, res);
        return res;
    }

    private Battle requireOpenBattle(long now) {
        if (battle == null || now >= battle.closesAt()) {
            throw new RoomException("no_battle", "There is no open Soundclash.");
        }
        return battle;
    }

    private void resolveBattleInternal(long now, boolean forced, Result res) {
        Battle b = battle;
        battle = null;
        Battle.Candidate lead = b.leader();
        String reason;
        Battle.Candidate winner = null;
        if (lead == null) {
            reason = "no_candidates";
        } else if (b.totalVotes() == 0 && !forced) {
            reason = "no_votes";
        } else {
            winner = lead;
            reason = forced ? "forced" : b.isTied() ? "tie_break" : "votes";
        }
        boolean queued = false;
        if (winner != null) {
            if (!queue.contains(winner.trackId())) {
                if (queue.size() < QueueState.MAX_SIZE) {
                    queue.add(winner.trackId(), winner.title(), winner.durationSeconds(), winner.submittedBy(), 0);
                }
            }
            queued = queue.promote(winner.trackId());
        }
        List<Effect.BattleCandidate> saved = new ArrayList<>();
        for (Battle.Candidate c : b.tally()) {
            saved.add(new Effect.BattleCandidate(c.trackId(), c.submittedBy(), c.votes()));
        }
        res.effects().add(new Effect.SaveBattle(b.openedAt(), now, winner == null ? null : winner.trackId(), saved));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("battleId", b.id());
        p.put("winnerTrackId", winner == null ? null : winner.trackId());
        p.put("reason", reason);
        p.put("queued", queued);
        p.put("tally", tallyPayload(b));
        res.events().add(broadcast("lounge.soundclash.resolved", p, res));
        res.log("soundclash.resolved", null, Map.of("battleId", b.id(), "reason", reason));
        if (queued) {
            queueChanged("soundclash", res);
        }
    }

    private Map<String, Object> battlePayload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("battleId", battle.id());
        p.put("openedAt", battle.openedAt());
        p.put("closesAt", battle.closesAt());
        p.put("maxCandidates", Battle.MAX_CANDIDATES);
        p.put("candidates", tallyPayload(battle));
        return p;
    }

    private static List<Map<String, Object>> tallyPayload(Battle b) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Battle.Candidate c : b.tally()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trackId", c.trackId());
            m.put("title", c.title());
            m.put("submittedBy", c.submittedBy());
            m.put("votes", c.votes());
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ sync

    public Result pong(String userId, long clientTs, long now) {
        requireMember(userId);
        Result res = new Result();
        res.events().add(direct(userId, "lounge.sync.pong",
                Map.of("clientTs", clientTs, "serverTs", now), 0));
        return res;
    }

    public void reportRtt(String userId, long rttMs) {
        Member m = members.get(userId);
        if (m != null && rttMs >= 0) {
            m.rttMs = rttMs;
        }
    }

    // ------------------------------------------------------------------ snapshot

    public Map<String, Object> snapshot(long now) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("loungeId", loungeId);
        p.put("status", status);
        p.put("locked", locked);
        p.putAll(hostPayload());
        p.put("nowPlaying", nowPlayingPayload(now));
        p.put("queue", queuePayload());
        p.put("participants", participants());
        p.put("sentiment", round3(mood.current(now)));
        p.put("seq", seq);
        p.put("serverTime", now);
        if (battle != null) {
            p.put("battle", battlePayload());
        }
        if (suggestion != null) {
            p.put("pendingSuggestion", Map.of("selectedTrackId", suggestion.selectedTrackId(),
                    "transitionScript", suggestion.transitionScript(), "autoApproveAt", suggestion.autoApproveAt()));
        }
        return p;
    }

    // ------------------------------------------------------------------ internals

    private void advanceInternal(long now, String reason, Result res) {
        if (nowPlaying != null) {
            recent.addFirst(nowPlaying.trackId());
            while (recent.size() > RECENT_MAX) {
                recent.removeLast();
            }
            closeTrackInteractions(now, "track_ended".equals(reason), res);
        }
        QueueState.Entry next = queue.pop();
        segueRequestedForCurrent = false;
        if (next == null) {
            nowPlaying = null;
        } else {
            nowPlaying = new TrackRef(next.trackId, next.title, next.durationSeconds);
            nowPlayingStartedAt = now;
            startTrackAudience();
            if (suggestion != null && suggestion.selectedTrackId().equals(next.trackId)) {
                suggestion = null;
            }
            if (next.trackId.equals(approvedScriptTrackId) && approvedScript != null) {
                res.events().add(broadcast("lounge.ai.dj_announcement", Map.of("trackId", next.trackId,
                        "script", approvedScript), res));
            }
        }
        approvedScriptTrackId = null;
        approvedScript = null;
        Map<String, Object> adv = new LinkedHashMap<>();
        adv.put("reason", reason);
        if (nowPlaying != null) {
            adv.put("trackId", nowPlaying.trackId());
        }
        res.log("queue.advance", null, adv);
        queueChanged(reason, res);
    }

    /** Everyone online when a track starts counts as having heard it. */
    private void startTrackAudience() {
        presentThisTrack.clear();
        likedThisTrack.clear();
        for (Member m : members.values()) {
            if (presence.isOnline(m.userId)) {
                presentThisTrack.add(m.userId);
            }
        }
    }

    /** One interaction row per listener who heard the track: how long, whether they finished, whether they liked it. */
    private void closeTrackInteractions(long now, boolean ended, Result res) {
        if (nowPlaying == null) {
            return;
        }
        int played = (int) Math.max(0, Math.min(nowPlaying.durationSeconds(), (now - nowPlayingStartedAt) / 1000));
        boolean completed = ended || played >= 0.8 * nowPlaying.durationSeconds();
        for (String uid : presentThisTrack) {
            if (members.containsKey(uid)) {
                res.effects().add(new Effect.SaveInteraction(uid, nowPlaying.trackId(), played, completed,
                        likedThisTrack.contains(uid)));
            }
        }
        presentThisTrack.clear();
        likedThisTrack.clear();
    }

    private int trackSeconds(long now) {
        return nowPlaying == null ? 0 : (int) Math.max(0, (now - nowPlayingStartedAt) / 1000);
    }

    private void queueChanged(String reason, Result res) {
        dirty = true;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("reason", reason);
        p.put("nowPlaying", nowPlayingPayload(-1));
        p.put("queue", queuePayload());
        res.events().add(broadcast("lounge.queue.update", p, res));
    }

    private void recomputeHost(Result res) {
        String before = actingHostId;
        recomputeHostQuiet();
        if (!java.util.Objects.equals(before, actingHostId) || before == null) {
            res.events().add(broadcast("lounge.host.changed", hostPayload(), res));
        }
    }

    private void recomputeHostQuiet() {
        actingHostId = members.values().stream()
                .filter(m -> m.loungeRole == Role.CO_DJ && presence.isOnline(m.userId))
                .min(Comparator.comparingLong(m -> m.joinSeq))
                .map(m -> m.userId)
                .orElse(null);
    }

    private Map<String, Object> hostPayload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("hostUserId", ownerUserId);
        p.put("hostState", hostState().name().toLowerCase());
        p.put("actingHostUserId", actingHostId);
        return p;
    }

    private Map<String, Object> nowPlayingPayload(long now) {
        if (nowPlaying == null) {
            return null;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("trackId", nowPlaying.trackId());
        p.put("title", nowPlaying.title());
        p.put("durationSeconds", nowPlaying.durationSeconds());
        p.put("startedAt", nowPlayingStartedAt);
        return p;
    }

    private List<Map<String, Object>> queuePayload() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (QueueState.Slot s : queue.snapshot()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trackId", s.trackId());
            m.put("title", s.title());
            m.put("durationSeconds", s.durationSeconds());
            m.put("voteCount", s.voteCount());
            m.put("pinned", s.pinned());
            m.put("addedBy", s.addedBy());
            m.put("position", s.position());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> participants() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Member m : members.values()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("userId", m.userId);
            p.put("name", m.name);
            p.put("role", effectiveRole(m.userId).value());
            p.put("online", presence.isOnline(m.userId));
            p.put("muted", m.muted);
            out.add(p);
        }
        return out;
    }

    private Map<String, Object> presencePayload(String userId, String change) {
        Member m = members.get(userId);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("userId", userId);
        p.put("name", m == null ? null : m.name);
        p.put("change", change);
        p.put("onlineCount", presence.onlineCount());
        return p;
    }

    private OutEvent broadcast(String name, Map<String, Object> payload, Result res) {
        return new OutEvent(name, payload, null, ++seq);
    }

    private OutEvent direct(String userId, String name, Map<String, Object> payload) {
        return direct(userId, name, payload, seq);
    }

    private OutEvent direct(String userId, String name, Map<String, Object> payload, long s) {
        return new OutEvent(name, payload, userId, s);
    }

    private Member requireMember(String userId) {
        Member m = members.get(userId);
        if (m == null) {
            throw new RoomException("not_a_member", "You are not a participant of this lounge.");
        }
        return m;
    }

    private Member requireTarget(String actorId, String targetId) {
        Member t = members.get(targetId);
        if (t == null) {
            throw new RoomException("not_found", "That participant is not in the lounge.");
        }
        if (targetId.equals(ownerUserId) || t.globalRole == Role.ADMIN || targetId.equals(actorId)) {
            throw new RoomException("forbidden", "You cannot moderate the host, an admin or yourself.");
        }
        return t;
    }

    private boolean can(String userId, Permission p) {
        Role r = effectiveRole(userId);
        return r != null && p.allows(r.value());
    }

    private void requirePermission(String userId, Permission p) {
        requireMember(userId);
        if (!can(userId, p)) {
            throw new RoomException("forbidden", "Your role does not allow this action.");
        }
    }

    private void requireUnfrozen() {
        if (hostState() == HostState.FROZEN) {
            throw new RoomException("room_frozen",
                    "The host is away and no co-DJ is online; the queue is frozen until they return.");
        }
    }

    private void requireNotEnded() {
        if ("ended".equals(status)) {
            throw new RoomException("lounge_ended", "This lounge has ended.");
        }
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
