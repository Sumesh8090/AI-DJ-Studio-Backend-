package com.djstudio.service;

import com.djstudio.analytics.SentimentTimeline;
import com.djstudio.repo.LoungeParticipantRepository;
import com.djstudio.repo.TrackRepository;
import com.djstudio.web.dto.AnalyticsDtos.LoungeAnalytics;
import com.djstudio.web.dto.AnalyticsDtos.Overview;
import com.djstudio.web.dto.AnalyticsDtos.SoundclashStats;
import com.djstudio.web.dto.AnalyticsDtos.TopTrack;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only analytics for the dashboard (PRD Epic 3). Counts come from the append-only queue_events log and the chat
 * table; the log is written in one-second batches, so numbers can lag the live room by about a second.
 */
@Service
public class AnalyticsService {

    private static final int MAX_CHAT_POINTS = 5000;

    private final JdbcTemplate jdbc;
    private final TrackRepository tracks;
    private final LoungeParticipantRepository participants;

    public AnalyticsService(JdbcTemplate jdbc, TrackRepository tracks, LoungeParticipantRepository participants) {
        this.jdbc = jdbc;
        this.tracks = tracks;
        this.participants = participants;
    }

    @Transactional(readOnly = true)
    public LoungeAnalytics lounge(String loungeId, String status, Instant endedAt, long onlineNow, long bucketMs) {
        Map<String, Long> byType = new HashMap<>();
        jdbc.query("SELECT event_type, COUNT(*) FROM queue_events WHERE lounge_id = ? GROUP BY event_type",
                rs -> {
                    byType.put(rs.getString(1), rs.getLong(2));
                }, loungeId);

        Long played = jdbc.queryForObject("SELECT COUNT(*) FROM queue_events WHERE lounge_id = ? "
                + "AND event_type = 'queue.advance' AND JSON_EXTRACT(payload, '$.trackId') IS NOT NULL",
                Long.class, loungeId);

        List<TopTrack> top = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        jdbc.query("SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.trackId')) AS tid, COUNT(*) AS c FROM queue_events "
                        + "WHERE lounge_id = ? AND event_type = 'queue.vote' GROUP BY tid ORDER BY c DESC, tid LIMIT 5",
                rs -> {
                    rows.add(new Object[] {rs.getString(1), rs.getLong(2)});
                }, loungeId);
        Map<String, String> titles = new HashMap<>();
        tracks.findAllById(rows.stream().map(r -> (String) r[0]).toList()).forEach(t -> titles.put(t.getId(), t.getTitle()));
        for (Object[] r : rows) {
            top.add(new TopTrack((String) r[0], titles.getOrDefault((String) r[0], (String) r[0]), (Long) r[1]));
        }

        List<SentimentTimeline.Point> points = jdbc.query(
                "SELECT created_at, sentiment_score FROM chat_messages WHERE lounge_id = ? AND sentiment_score IS NOT NULL "
                        + "ORDER BY created_at LIMIT " + MAX_CHAT_POINTS,
                (rs, i) -> new SentimentTimeline.Point(rs.getTimestamp(1).getTime(), rs.getDouble(2)), loungeId);

        Long battles = jdbc.queryForObject("SELECT COUNT(*) FROM soundclash_battles WHERE lounge_id = ?", Long.class, loungeId);
        Long withWinner = jdbc.queryForObject("SELECT COUNT(*) FROM soundclash_battles WHERE lounge_id = ? "
                + "AND winning_track_id IS NOT NULL", Long.class, loungeId);
        Long clashVotes = jdbc.queryForObject("SELECT COALESCE(SUM(c.vote_count), 0) FROM soundclash_candidates c "
                + "JOIN soundclash_battles b ON b.id = c.battle_id WHERE b.lounge_id = ?", Long.class, loungeId);

        java.sql.Timestamp startedTs = jdbc.queryForObject("SELECT MIN(created_at) FROM queue_events WHERE lounge_id = ? "
                + "AND event_type = 'lounge.start'", java.sql.Timestamp.class, loungeId);
        Instant startedAt = startedTs == null ? null : startedTs.toInstant();
        Instant until = endedAt != null ? endedAt : Instant.now();
        Long duration = startedAt == null ? null : Math.max(0, until.getEpochSecond() - startedAt.getEpochSecond());
        Long peak = jdbc.queryForObject("SELECT COALESCE(MAX(CAST(JSON_UNQUOTE(JSON_EXTRACT(payload, '$.count')) AS UNSIGNED)), 0) "
                + "FROM queue_events WHERE lounge_id = ? AND event_type = 'presence.peak'", Long.class, loungeId);

        return new LoungeAnalytics(loungeId, status, startedAt, duration, nz(peak), byType.getOrDefault("ai.ask", 0L),
                participants.countByLoungeIdAndLeftAtIsNull(loungeId), onlineNow,
                nz(played), byType.getOrDefault("queue.add", 0L), byType.getOrDefault("queue.vote", 0L),
                points.size(), SentimentTimeline.summarise(points), SentimentTimeline.bucket(points, bucketMs), top,
                new SoundclashStats(nz(battles), nz(withWinner), nz(clashVotes)), Instant.now());
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        Double avg = jdbc.queryForObject("SELECT AVG(sentiment_score) FROM chat_messages WHERE sentiment_score IS NOT NULL",
                Double.class);
        return new Overview(count("dj_lounges"), count("users"), count("tracks"), count("interactions"),
                count("chat_messages"), avg == null ? 0.5 : Math.round(avg * 1000.0) / 1000.0, Instant.now());
    }

    private long count(String table) { // table names are constants from this class, never user input
        return nz(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }
}
