package com.djstudio.service;

import com.djstudio.analytics.TasteProfile;
import com.djstudio.reco.Interaction;
import com.djstudio.web.dto.DashboardDtos.ActiveRoom;
import com.djstudio.web.dto.DashboardDtos.LoungeHistoryItem;
import com.djstudio.web.dto.DashboardDtos.PersonalDashboard;
import com.djstudio.ws.RoomService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The user's home base outside a live session (PRD 6.3): history, taste profile, quick rejoin, recommendations. */
@Service
public class PersonalDashboardService {

    private final JdbcTemplate jdbc;
    private final RecommendationService recommendations;
    private final RoomService rooms;

    public PersonalDashboardService(JdbcTemplate jdbc, RecommendationService recommendations, RoomService rooms) {
        this.jdbc = jdbc;
        this.recommendations = recommendations;
        this.rooms = rooms;
    }

    @Transactional(readOnly = true)
    public PersonalDashboard forUser(String userId) {
        List<LoungeHistoryItem> history = new ArrayList<>();
        List<ActiveRoom> active = new ArrayList<>();
        jdbc.query("SELECT l.id, l.vibe_profile, l.status, l.created_at, l.ended_at, p.lounge_role, p.joined_at, p.left_at "
                + "FROM lounge_participants p JOIN dj_lounges l ON l.id = p.lounge_id WHERE p.user_id = ? "
                + "ORDER BY p.joined_at DESC LIMIT 20", rs -> {
            String id = rs.getString(1);
            String status = rs.getString(3);
            String role = rs.getString(6);
            Timestamp ended = rs.getTimestamp(5);
            history.add(new LoungeHistoryItem(id, rs.getString(2), status, role, "host".equals(role),
                    rs.getTimestamp(7).toInstant(), rs.getTimestamp(4).toInstant(), ended == null ? null : ended.toInstant()));
            if (!"ended".equals(status) && rs.getTimestamp(8) == null) {
                active.add(new ActiveRoom(id, rs.getString(2), status, role, rooms.onlineCount(id),
                        "/ws/lounges/" + id));
            }
        }, userId);

        List<TasteProfile.Entry> taste = jdbc.query(
                "SELECT t.bpm, t.camelot_key, t.energy, t.valence, i.played_seconds, i.completed, i.liked "
                        + "FROM interactions i JOIN tracks t ON t.id = i.track_id WHERE i.user_id = ? "
                        + "ORDER BY i.created_at DESC LIMIT 2000",
                (rs, n) -> new TasteProfile.Entry(rs.getDouble(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4),
                        new Interaction(userId, "", rs.getInt(5), rs.getBoolean(6), rs.getBoolean(7)).weight()),
                userId);

        return new PersonalDashboard(userId, history, active, TasteProfile.of(taste),
                recommendations.recommend(userId, 5).tracks(), Instant.now());
    }
}
