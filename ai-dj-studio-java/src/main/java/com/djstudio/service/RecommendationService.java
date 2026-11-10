package com.djstudio.service;

import com.djstudio.core.TrackFeatures;
import com.djstudio.domain.Track;
import com.djstudio.reco.Interaction;
import com.djstudio.reco.Recommendation;
import com.djstudio.reco.Recommender;
import com.djstudio.repo.TrackRepository;
import com.djstudio.web.ApiException;
import com.djstudio.web.dto.RecommendationDtos.InteractionRequest;
import com.djstudio.web.dto.RecommendationDtos.RecommendationResponse;
import com.djstudio.web.dto.RecommendationDtos.RecommendedTrack;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds a {@link Recommender} from the catalogue and the recent interactions on each request. That is fine for a
 * catalogue of hundreds of tracks and a few hundred thousand interactions; at larger scale, cache or precompute the
 * item-item similarities (noted in the README).
 */
@Service
public class RecommendationService {

    private static final int MAX_INTERACTIONS = 200_000;

    private final TrackRepository tracks;
    private final JdbcTemplate jdbc;

    public RecommendationService(TrackRepository tracks, JdbcTemplate jdbc) {
        this.tracks = tracks;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public RecommendationResponse recommend(String userId, int limit) {
        Map<String, Track> byId = new LinkedHashMap<>();
        Map<String, TrackFeatures> catalog = new HashMap<>();
        for (Track t : tracks.findAll()) {
            byId.put(t.getId(), t);
            catalog.put(t.getId(), new TrackFeatures(t.getId(), t.getBpm(), t.getCamelotKey(), t.getEnergy(),
                    t.getValence(), t.getTitle()));
        }
        List<Interaction> interactions = jdbc.query(
                "SELECT user_id, track_id, played_seconds, completed, liked FROM interactions "
                        + "ORDER BY created_at DESC LIMIT " + MAX_INTERACTIONS,
                (rs, i) -> new Interaction(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4),
                        rs.getBoolean(5)));
        List<RecommendedTrack> out = new ArrayList<>();
        for (Recommendation r : new Recommender(catalog, interactions).recommend(userId, limit)) {
            Track t = byId.get(r.trackId());
            out.add(new RecommendedTrack(t.getId(), t.getTitle(), t.getArtist(), r.score(), r.source(), t.getBpm(),
                    t.getCamelotKey()));
        }
        return new RecommendationResponse(userId, out, Instant.now());
    }

    @Transactional
    public void record(String userId, InteractionRequest req) {
        if (!tracks.existsById(req.trackId())) {
            throw new ApiException(404, "track_not_found", "Track " + req.trackId() + " does not exist.");
        }
        jdbc.update("INSERT INTO interactions (user_id, track_id, played_seconds, completed, liked, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", userId, req.trackId(),
                req.playedSeconds() == null ? 0 : req.playedSeconds(), Boolean.TRUE.equals(req.completed()),
                Boolean.TRUE.equals(req.liked()), Timestamp.from(Instant.now()));
    }
}
