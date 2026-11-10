package com.djstudio.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Append-only room activity log (queue_events). Rows are buffered in memory and written in batches once a second,
 * so a vote never costs a database round-trip on the hot path (PRD 9.4). The buffer is bounded: if the database is
 * down, the oldest rows are dropped rather than the gateway running out of memory.
 */
@Component
public class EventLogWriter {

    private static final Logger log = LoggerFactory.getLogger(EventLogWriter.class);
    private static final int MAX_BUFFER = 20_000;
    private static final int BATCH = 500;

    private record Row(String loungeId, String type, String userId, String payloadJson, Instant at) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final BlockingQueue<Row> buffer = new LinkedBlockingQueue<>(MAX_BUFFER);

    public EventLogWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void enqueue(String loungeId, String type, String userId, Map<String, Object> payload) {
        String json = null;
        if (payload != null && !payload.isEmpty()) {
            try {
                json = mapper.writeValueAsString(payload);
            } catch (JsonProcessingException e) {
                log.warn("queue_events payload not serialisable for type {}", type);
            }
        }
        Row row = new Row(loungeId, type, userId, json, Instant.now());
        while (!buffer.offer(row)) {
            buffer.poll(); // full: drop the oldest
        }
    }

    @Scheduled(fixedDelay = 1000)
    @PreDestroy
    public void flush() {
        List<Row> rows = new ArrayList<>();
        buffer.drainTo(rows, BATCH);
        if (rows.isEmpty()) {
            return;
        }
        try {
            jdbc.batchUpdate(
                    "INSERT INTO queue_events (lounge_id, event_type, user_id, payload, created_at) VALUES (?,?,?,?,?)",
                    rows, rows.size(), (ps, r) -> {
                        ps.setString(1, r.loungeId());
                        ps.setString(2, r.type());
                        ps.setString(3, r.userId());
                        ps.setString(4, r.payloadJson());
                        ps.setTimestamp(5, Timestamp.from(r.at()));
                    });
        } catch (RuntimeException e) {
            // e.g. the lounge was deleted meanwhile; the log is best-effort and must never break a room.
            log.atWarn().addKeyValue("rows", rows.size()).log("queue_events batch failed: " + e.getClass().getSimpleName());
        }
    }

    int pending() {
        return buffer.size();
    }
}
