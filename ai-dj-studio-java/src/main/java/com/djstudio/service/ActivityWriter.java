package com.djstudio.service;

import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Batched writer for chat messages (with their sentiment score) and listener interactions, so the live room never
 * waits on the database. Same bounded-buffer, best-effort policy as {@link EventLogWriter}.
 */
@Component
public class ActivityWriter {

    private static final Logger log = LoggerFactory.getLogger(ActivityWriter.class);
    private static final int MAX_BUFFER = 20_000;
    private static final int BATCH = 500;

    private record ChatRow(String loungeId, String userId, String body, double sentiment, int tsSeconds, Instant at) {
    }

    private record InteractionRow(String userId, String trackId, int playedSeconds, boolean completed, boolean liked,
                                  Instant at) {
    }

    private final JdbcTemplate jdbc;
    private final BlockingQueue<ChatRow> chat = new LinkedBlockingQueue<>(MAX_BUFFER);
    private final BlockingQueue<InteractionRow> interactions = new LinkedBlockingQueue<>(MAX_BUFFER);

    public ActivityWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void chat(String loungeId, String userId, String body, double sentiment, int tsSeconds) {
        ChatRow row = new ChatRow(loungeId, userId, body, sentiment, tsSeconds, Instant.now());
        while (!chat.offer(row)) {
            chat.poll();
        }
    }

    public void interaction(String userId, String trackId, int playedSeconds, boolean completed, boolean liked) {
        InteractionRow row = new InteractionRow(userId, trackId, playedSeconds, completed, liked, Instant.now());
        while (!interactions.offer(row)) {
            interactions.poll();
        }
    }

    @Scheduled(fixedDelay = 1000)
    @PreDestroy
    public void flush() {
        List<ChatRow> chats = new ArrayList<>();
        chat.drainTo(chats, BATCH);
        if (!chats.isEmpty()) {
            try {
                jdbc.batchUpdate("INSERT INTO chat_messages (lounge_id, user_id, msg_type, body, sentiment_score, "
                        + "ts_seconds, created_at) VALUES (?, ?, 'chat', ?, ?, ?, ?)", chats, chats.size(), (ps, r) -> {
                    ps.setString(1, r.loungeId());
                    ps.setString(2, r.userId());
                    ps.setString(3, r.body());
                    ps.setDouble(4, r.sentiment());
                    ps.setInt(5, r.tsSeconds());
                    ps.setTimestamp(6, Timestamp.from(r.at()));
                });
            } catch (RuntimeException e) {
                log.atWarn().addKeyValue("rows", chats.size()).log("chat batch failed: " + e.getClass().getSimpleName());
            }
        }
        List<InteractionRow> rows = new ArrayList<>();
        interactions.drainTo(rows, BATCH);
        if (!rows.isEmpty()) {
            try {
                jdbc.batchUpdate("INSERT INTO interactions (user_id, track_id, played_seconds, completed, liked, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, ?)", rows, rows.size(), (ps, r) -> {
                    ps.setString(1, r.userId());
                    ps.setString(2, r.trackId());
                    ps.setInt(3, r.playedSeconds());
                    ps.setBoolean(4, r.completed());
                    ps.setBoolean(5, r.liked());
                    ps.setTimestamp(6, Timestamp.from(r.at()));
                });
            } catch (RuntimeException e) {
                log.atWarn().addKeyValue("rows", rows.size())
                        .log("interaction batch failed: " + e.getClass().getSimpleName());
            }
        }
    }
}
