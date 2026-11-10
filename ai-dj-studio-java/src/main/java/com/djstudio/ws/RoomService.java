package com.djstudio.ws;

import com.djstudio.ai.AiDjService;
import com.djstudio.ai.Retriever;
import com.djstudio.ai.TransitionWriter;
import com.djstudio.config.AiProperties;
import com.djstudio.core.Role;
import com.djstudio.core.Sanitizer;
import com.djstudio.repo.TrackRepository;
import com.djstudio.repo.UserRepository;
import com.djstudio.room.Effect;
import com.djstudio.room.QueueState;
import com.djstudio.room.Result;
import com.djstudio.room.Room;
import com.djstudio.room.RoomException;
import com.djstudio.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.djstudio.service.EventLogWriter;
import com.djstudio.service.LoungePersistence;
import io.jsonwebtoken.Claims;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;

/**
 * The gateway's brain: connects/disconnects sockets, routes client commands to the {@link Room}, performs the
 * effects the room asks for, and drives the AI DJ. Every authorisation decision is made inside the Room using the
 * caller's role in THIS lounge (never a role claimed by the client).
 */
@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);
    private static final long EVICT_AFTER_IDLE_MS = 60_000;

    private record QueueSnapshot(List<QueueState.Slot> slots, String nowPlayingTrackId) {
    }

    private final RoomRegistry registry;
    private final LoungePersistence persistence;
    private final AiDjService ai;
    private final AiProperties aiProps;
    private final TrackRepository tracks;
    private final UserRepository users;
    private final JwtService jwt;
    private final ObjectMapper mapper;
    private final EventLogWriter eventLog;
    private final ExecutorService aiExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ai-dj");
        t.setDaemon(true);
        return t;
    });

    RoomService(RoomRegistry registry, LoungePersistence persistence, AiDjService ai, AiProperties aiProps,
                TrackRepository tracks, UserRepository users, JwtService jwt, ObjectMapper mapper,
                EventLogWriter eventLog) {
        this.registry = registry;
        this.persistence = persistence;
        this.ai = ai;
        this.aiProps = aiProps;
        this.tracks = tracks;
        this.users = users;
        this.jwt = jwt;
        this.mapper = mapper;
        this.eventLog = eventLog;
    }

    @PreDestroy
    void shutdown() {
        aiExecutor.shutdown();
        for (RoomRuntime rt : registry.all()) {
            flushQueue(rt);
            rt.closeAll(CloseStatus.SERVICE_RESTARTED);
        }
    }

    // ------------------------------------------------------------------ read helpers for REST

    public List<String> recentTrackIds(String loungeId) {
        return registry.peek(loungeId).map(rt -> rt.read(r -> r.aiContext(now()).recentTrackIds())).orElse(List.of());
    }

    /** Records an AI question (WebSocket or REST) for the session analytics. */
    public void logAiQuestion(String loungeId, String userId, boolean grounded) {
        eventLog.enqueue(loungeId, "ai.ask", userId, Map.of("grounded", grounded));
    }

    /** Live rooms and open connections, for the gateway health check. */
    public int liveRooms() {
        return registry.all().size();
    }

    public int openConnections() {
        int n = 0;
        for (RoomRuntime rt : registry.all()) {
            n += rt.connectionCount();
        }
        return n;
    }

    public long onlineCount(String loungeId) {
        return registry.peek(loungeId).map(rt -> (long) rt.read(Room::onlineCount)).orElse(0L);
    }

    public List<String> queuedTrackIds(String loungeId) {
        return registry.peek(loungeId).map(rt -> rt.read(Room::queueTrackIds)).orElse(List.of());
    }

    public String nowPlayingTrackId(String loungeId, String fallback) {
        return registry.peek(loungeId)
                .map(rt -> rt.read(r -> r.nowPlaying() == null ? fallback : r.nowPlaying().trackId()))
                .orElse(fallback);
    }

    // ------------------------------------------------------------------ connection lifecycle

    /** Called after the handshake passed. Returns false if the lounge no longer exists. */
    boolean connect(Conn conn, String loungeId, String name, Role globalRole, Role loungeRole, boolean muted) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<RoomRuntime> found = registry.getOrLoad(loungeId);
            if (found.isEmpty()) {
                return false;
            }
            RoomRuntime rt = found.get();
            if (!rt.attach(conn)) {
                registry.evict(rt);
                continue; // it was evicted between lookup and attach: load a fresh one
            }
            try {
                after(rt, rt.execute(room -> {
                    room.addMember(conn.userId, name, globalRole, loungeRole, muted);
                    return room.connect(conn.userId, now());
                }));
            } catch (RoomException e) {
                rt.detach(conn, now());
                throw e;
            }
            return true;
        }
        return false;
    }

    void disconnect(Conn conn, String loungeId) {
        registry.peek(loungeId).ifPresent(rt -> {
            rt.detach(conn, now());
            after(rt, rt.execute(room -> room.disconnect(conn.userId, now())));
        });
    }

    // ------------------------------------------------------------------ commands

    void handle(Conn conn, String loungeId, String text) {
        RoomRuntime rt = registry.peek(loungeId).orElse(null);
        if (rt == null) {
            return;
        }
        String id = null;
        try {
            JsonNode msg = mapper.readTree(text);
            if (msg == null || !msg.isObject()) {
                throw new RoomException("invalid_message", "Messages must be JSON objects.");
            }
            id = msg.path("id").isTextual() ? msg.get("id").asText() : null;
            String event = msg.path("event").asText("");
            JsonNode p = msg.path("payload");

            if (!conn.generalLimiter.tryAcquire(now())) {
                throw new RoomException("rate_limited", "You are sending commands too quickly.");
            }
            if (now() >= conn.tokenExpiresAtMs && !"auth.refresh".equals(event)) {
                rt.sendDirect(conn, "lounge.error", error("token_expired", "Your session expired; send auth.refresh.", id));
                rt.closeUser(conn.userId, new CloseStatus(4401, "token_expired"));
                return;
            }
            dispatch(rt, conn, event, p, id);
            if (id != null) {
                rt.sendDirect(conn, "lounge.ack", Map.of("id", id, "event", event));
            }
        } catch (RoomException e) {
            rt.sendDirect(conn, "lounge.error", error(e.code(), e.getMessage(), id));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            rt.sendDirect(conn, "lounge.error", error("invalid_message", "Messages must be valid JSON.", id));
        }
    }

    private void dispatch(RoomRuntime rt, Conn c, String event, JsonNode p, String id) {
        String uid = c.userId;
        switch (event) {
            case "queue.vote" -> run(rt, r -> r.vote(uid, str(p, "trackId"), now()));
            case "queue.unvote" -> run(rt, r -> r.unvote(uid, str(p, "trackId"), now()));
            case "queue.add" -> {
                String trackId = str(p, "trackId");
                Room.TrackRef ref = tracks.findById(trackId)
                        .map(t -> new Room.TrackRef(t.getId(), t.getTitle(), t.getDurationSeconds()))
                        .orElseThrow(() -> new RoomException("track_not_found", "Unknown track " + trackId + "."));
                run(rt, r -> r.add(uid, ref, now()));
            }
            case "queue.remove" -> run(rt, r -> r.remove(uid, str(p, "trackId"), now()));
            case "queue.promote" -> run(rt, r -> r.promote(uid, str(p, "trackId"), now()));
            case "queue.advance" -> run(rt, r -> r.advance(uid, now()));
            case "lounge.start" -> run(rt, r -> r.start(uid, now()));
            case "lounge.end" -> run(rt, r -> r.end(uid, now()));
            case "ai.segue.request" -> run(rt, r -> r.requestSegue(uid), c);
            case "ai.segue.approve" -> run(rt, r -> r.approveSuggestion(uid, now()));
            case "ai.segue.reject" -> run(rt, r -> r.rejectSuggestion(uid, now()));
            case "ai.ask" -> ask(rt, c, p, id);
            case "soundclash.open" -> run(rt, r -> r.openBattle(uid, now()));
            case "soundclash.submit" -> {
                String trackId = str(p, "trackId");
                Room.TrackRef ref = tracks.findById(trackId)
                        .map(t -> new Room.TrackRef(t.getId(), t.getTitle(), t.getDurationSeconds()))
                        .orElseThrow(() -> new RoomException("track_not_found", "Unknown track " + trackId + "."));
                Room.TrackRef current = rt.read(Room::nowPlaying);
                double tol = rt.read(r -> r.config().bpmTolerancePct());
                boolean compatible = current != null && ai.isCompatible(current.trackId(), trackId, tol);
                run(rt, r -> r.submitBattle(uid, ref, compatible, now()));
            }
            case "soundclash.vote" -> run(rt, r -> r.voteBattle(uid, str(p, "trackId"), now()));
            case "soundclash.resolve" -> run(rt, r -> r.resolveBattle(uid, now()));
            case "chat.send" -> {
                if (!c.chatLimiter.tryAcquire(now())) {
                    throw new RoomException("rate_limited", "Slow down: too many chat messages.");
                }
                run(rt, r -> r.chat(uid, text(p, "text"), now()));
            }
            case "chat.typing" -> run(rt, r -> r.typing(uid, p.path("typing").asBoolean(true)));
            case "chat.react" -> run(rt, r -> r.react(uid, str(p, "reaction"), now()));
            case "mod.lock" -> run(rt, r -> r.setLocked(uid, p.path("locked").asBoolean(true)));
            case "mod.mute" -> run(rt, r -> r.mute(uid, str(p, "userId"), p.path("muted").asBoolean(true)));
            case "mod.kick" -> run(rt, r -> r.kick(uid, str(p, "userId"), now()));
            case "mod.setRole" -> {
                Role role = Role.fromValue(str(p, "role"))
                        .orElseThrow(() -> new RoomException("invalid_role", "Unknown role."));
                run(rt, r -> r.setRole(uid, str(p, "userId"), role, now()));
            }
            case "lounge.sync.ping" -> run(rt, r -> r.pong(uid, p.path("clientTs").asLong(0), now()));
            case "auth.refresh" -> refreshAuth(rt, c, str(p, "token"));
            default -> throw new RoomException("unknown_event", "Unknown event '" + event + "'.");
        }
    }

    private void run(RoomRuntime rt, java.util.function.Function<Room, Result> op) {
        run(rt, op, null);
    }

    private void run(RoomRuntime rt, java.util.function.Function<Room, Result> op, Conn requester) {
        Result res = rt.execute(op);
        after(rt, res, requester);
    }

    private void after(RoomRuntime rt, Result res) {
        after(rt, res, null);
    }

    /** Effects and AI triggers produced by an operation. Runs outside the room lock. */
    private void after(RoomRuntime rt, Result res, Conn requester) {
        List<Effect> persistable = res.effects().stream().filter(e -> !(e instanceof Effect.CloseSessions)).toList();
        for (Effect e : res.effects()) {
            if (e instanceof Effect.CloseSessions cs) {
                rt.closeUser(cs.userId(), new CloseStatus(4403, cs.reason()));
            }
        }
        if (!persistable.isEmpty()) {
            rt.effectsExecutor().submit(() -> {
                try {
                    persistence.apply(rt.loungeId(), persistable);
                } catch (RuntimeException ex) {
                    log.atError().addKeyValue("loungeId", rt.loungeId()).setCause(ex).log("persist effects failed");
                }
            });
        }
        if (res.wantsSegueSuggestion()) {
            suggestSegue(rt, requester);
        }
    }

    // ------------------------------------------------------------------ AI

    private void suggestSegue(RoomRuntime rt, Conn requester) {
        if (!aiProps.enabled()) {
            return;
        }
        aiExecutor.submit(() -> {
            try {
                Room.AiContext ctx = rt.read(r -> r.aiContext(now()));
                double tol = rt.read(r -> r.config().bpmTolerancePct());
                if (ctx.currentTrackId() == null) {
                    return;
                }
                var proposal = ai.propose(ctx.currentTrackId(), ctx.candidateTrackIds(), ctx.recentTrackIds(),
                        ctx.sentiment(), ctx.sentimentTrend(), tol, TransitionWriter.Verbosity.parse(rt.aiVerbosity()));
                if (proposal.isEmpty()) {
                    if (requester != null) {
                        rt.sendDirect(requester, "lounge.error", error("no_compatible_candidate",
                                "No queued track is BPM/key compatible with the current one.", null));
                    }
                    return;
                }
                var p = proposal.get();
                after(rt, rt.execute(r -> r.setSuggestion(p.selectedTrackId(), p.transitionScript(), p.matchReason(),
                        p.sourceEvents(), now())));
            } catch (RuntimeException ex) {
                log.atError().addKeyValue("loungeId", rt.loungeId()).setCause(ex).log("ai segue failed");
            }
        });
    }

    private void ask(RoomRuntime rt, Conn c, JsonNode p, String id) {
        if (!c.aiLimiter.tryAcquire(now())) {
            throw new RoomException("rate_limited", "Too many questions; try again in a moment.");
        }
        String q;
        try {
            q = Sanitizer.sanitize(p.path("question").asText(null), 300, "question");
        } catch (IllegalArgumentException e) {
            throw new RoomException("invalid_question", e.getMessage());
        }
        Room.AiContext ctx = rt.read(r -> r.aiContext(now()));
        Retriever.Answer a = ai.ask(q, ctx.currentTrackId(), ctx.candidateTrackIds());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("question", q);
        out.put("answer", a.text());
        out.put("grounded", a.grounded());
        out.put("citations", a.citations());
        logAiQuestion(rt.loungeId(), c.userId, a.grounded());
        rt.sendDirect(c, "lounge.ai.answer", out);
    }

    private void refreshAuth(RoomRuntime rt, Conn c, String token) {
        Claims claims;
        try {
            claims = jwt.parse(token, JwtService.TokenType.ACCESS);
        } catch (JwtService.TokenException e) {
            throw new RoomException("invalid_token", e.getMessage());
        }
        if (!claims.getSubject().equals(c.userId)) {
            throw new RoomException("invalid_token", "That token belongs to a different user.");
        }
        boolean active = users.findById(c.userId).map(u -> u.isActive()).orElse(false);
        if (!active) {
            rt.closeUser(c.userId, new CloseStatus(4403, "account_disabled"));
            return;
        }
        c.tokenExpiresAtMs = claims.getExpiration().getTime();
    }

    // ------------------------------------------------------------------ clock

    /** Called every second by the ticker. */
    void tick() {
        long now = now();
        for (RoomRuntime rt : registry.all()) {
            try {
                after(rt, rt.execute(r -> r.tick(now)));
                rt.closeExpired(now);
                flushQueue(rt);
                if (!rt.hasConnections() && rt.idleSince() > 0 && now - rt.idleSince() > EVICT_AFTER_IDLE_MS
                        && rt.read(Room::isIdle)) {
                    registry.evict(rt);
                    flushQueue(rt);
                    rt.closeAll(CloseStatus.NORMAL);
                }
            } catch (RuntimeException ex) {
                log.atError().addKeyValue("loungeId", rt.loungeId()).setCause(ex).log("room tick failed");
            }
        }
    }

    /** Persists the queue if it changed. Called at most once per tick, so it is debounced to about 1 write/s/room. */
    private void flushQueue(RoomRuntime rt) {
        QueueSnapshot snap = rt.read(r -> r.consumeDirty()
                ? new QueueSnapshot(r.queueSlots(), r.nowPlaying() == null ? null : r.nowPlaying().trackId())
                : null);
        if (snap == null) {
            return;
        }
        try {
            rt.effectsExecutor().submit(() -> persistence.saveQueue(rt.loungeId(), snap.slots(), snap.nowPlayingTrackId()));
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            persistence.saveQueue(rt.loungeId(), snap.slots(), snap.nowPlayingTrackId()); // shutting down: write inline
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String str(JsonNode p, String field) {
        JsonNode n = p.path(field);
        if (!n.isTextual() || n.asText().isBlank() || n.asText().length() > 64) {
            throw new RoomException("invalid_payload", "'" + field + "' is required (text, at most 64 characters).");
        }
        return n.asText();
    }

    private static String text(JsonNode p, String field) {
        JsonNode n = p.path(field);
        if (!n.isTextual()) {
            throw new RoomException("invalid_payload", "'" + field + "' must be text.");
        }
        return n.asText();
    }

    private static Map<String, Object> error(String code, String message, String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        m.put("id", id);
        return m;
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
