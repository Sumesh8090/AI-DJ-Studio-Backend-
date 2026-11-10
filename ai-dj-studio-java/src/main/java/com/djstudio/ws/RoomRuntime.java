package com.djstudio.ws;

import com.djstudio.room.OutEvent;
import com.djstudio.room.Result;
import com.djstudio.room.Room;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

/**
 * The live side of one room: the pure {@link Room} plus its open connections.
 *
 * <p>{@link #execute} is the single writer. It runs the room operation and delivers the resulting events while still
 * holding the lock, so every client receives broadcasts in exactly the order the room produced them (each carries a
 * monotonic {@code seq}). Each broadcast is serialised once, so all clients receive byte-identical messages.
 * Persistence effects are NOT done here (no database I/O under the lock); they run on this room's own
 * single-thread executor, which keeps them in order.
 */
final class RoomRuntime {

    private static final Logger log = LoggerFactory.getLogger(RoomRuntime.class);

    private final String loungeId;
    private final Room room;
    private final ObjectMapper mapper;
    private final List<Conn> conns = new ArrayList<>();
    private final ExecutorService effects;
    private volatile long idleSince = -1;
    private volatile String aiVerbosity = "normal";
    private boolean closed;

    RoomRuntime(String loungeId, Room room, ObjectMapper mapper) {
        this.loungeId = loungeId;
        this.room = room;
        this.mapper = mapper;
        this.effects = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "room-effects-" + loungeId);
            t.setDaemon(true);
            return t;
        });
    }

    String loungeId() {
        return loungeId;
    }

    String aiVerbosity() {
        return aiVerbosity;
    }

    void setAiVerbosity(String v) {
        this.aiVerbosity = v;
    }

    ExecutorService effectsExecutor() {
        return effects;
    }

    long idleSince() {
        return idleSince;
    }

    /** Runs one room operation atomically and delivers its events. Exceptions from the room propagate untouched. */
    synchronized Result execute(Function<Room, Result> op) {
        Result result = op.apply(room);
        for (OutEvent e : result.events()) {
            deliver(e);
        }
        return result;
    }

    /** Runs a read-only query against the room under the same lock. */
    synchronized <T> T read(Function<Room, T> query) {
        return query.apply(room);
    }

    /** @return false when this runtime was already evicted; the caller must load a fresh one and retry. */
    synchronized boolean attach(Conn c) {
        if (closed) {
            return false;
        }
        conns.add(c);
        idleSince = -1;
        return true;
    }

    synchronized void detach(Conn c, long now) {
        conns.remove(c);
        if (conns.isEmpty()) {
            idleSince = now;
        }
    }

    synchronized int connectionCount() {
        return conns.size();
    }

    synchronized boolean hasConnections() {
        return !conns.isEmpty();
    }

    synchronized void sendDirect(Conn c, String name, Map<String, Object> payload) {
        send(c, serialise(new OutEvent(name, payload, c.userId, room.seq())));
    }

    synchronized void closeUser(String userId, CloseStatus status) {
        for (Conn c : new ArrayList<>(conns)) {
            if (c.userId.equals(userId)) {
                close(c, status);
            }
        }
    }

    /** Closes connections whose access token has expired and were not refreshed with auth.refresh. */
    synchronized List<Conn> closeExpired(long now) {
        List<Conn> closed = new ArrayList<>();
        for (Conn c : new ArrayList<>(conns)) {
            if (now >= c.tokenExpiresAtMs) {
                close(c, new CloseStatus(4401, "token_expired"));
                closed.add(c);
            }
        }
        return closed;
    }

    synchronized void closeAll(CloseStatus status) {
        closed = true;
        for (Conn c : new ArrayList<>(conns)) {
            close(c, status);
        }
        effects.shutdown();
    }

    // ------------------------------------------------------------------ internals (lock held)

    private void deliver(OutEvent e) {
        TextMessage msg = serialise(e);
        if (msg == null) {
            return;
        }
        for (Conn c : new ArrayList<>(conns)) {
            if (e.isBroadcast() || c.userId.equals(e.toUserId())) {
                send(c, msg);
            }
        }
    }

    private TextMessage serialise(OutEvent e) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event", e.name());
        envelope.put("loungeId", loungeId);
        envelope.put("payload", e.payload());
        envelope.put("ts", System.currentTimeMillis());
        envelope.put("seq", e.seq());
        try {
            return new TextMessage(mapper.writeValueAsString(envelope));
        } catch (JsonProcessingException ex) {
            log.error("Could not serialise event {}", e.name(), ex);
            return null;
        }
    }

    private void send(Conn c, TextMessage msg) {
        if (msg == null) {
            return;
        }
        try {
            if (c.session.isOpen()) {
                c.session.sendMessage(msg);
            }
        } catch (IOException | RuntimeException ex) {
            // Slow or broken consumer (the decorator throws when its buffer/time limit is exceeded): drop it so it
            // cannot stall the room; the client reconnects and re-syncs from a fresh snapshot.
            log.atWarn().addKeyValue("loungeId", loungeId).addKeyValue("userId", c.userId)
                    .log("ws.drop_slow_consumer: " + ex.getClass().getSimpleName());
            close(c, CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    private void close(Conn c, CloseStatus status) {
        try {
            c.session.close(status);
        } catch (IOException | RuntimeException ignored) {
            // already gone
        }
    }
}
