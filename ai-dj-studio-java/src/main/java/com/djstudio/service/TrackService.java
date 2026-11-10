package com.djstudio.service;

import com.djstudio.core.CamelotWheel;
import com.djstudio.core.Ids;
import com.djstudio.core.Sanitizer;
import com.djstudio.core.SegueMatcher;
import com.djstudio.core.TrackFeatures;
import com.djstudio.domain.Track;
import com.djstudio.domain.TrackTimelineEvent;
import com.djstudio.repo.TrackRepository;
import com.djstudio.web.ApiException;
import com.djstudio.web.dto.TrackDtos.AcousticProfileResponse;
import com.djstudio.web.dto.TrackDtos.AcousticRequest;
import com.djstudio.web.dto.TrackDtos.AcousticResponse;
import com.djstudio.web.dto.TrackDtos.AcousticUpdateRequest;
import com.djstudio.web.dto.TrackDtos.CompatibleCandidate;
import com.djstudio.web.dto.TrackDtos.CompatibleTracksResponse;
import com.djstudio.web.dto.TrackDtos.RejectedCandidate;
import com.djstudio.web.dto.TrackDtos.TimelineEventRequest;
import com.djstudio.web.dto.TrackDtos.TimelineEventResponse;
import com.djstudio.web.dto.TrackDtos.TrackCreateRequest;
import com.djstudio.web.dto.TrackDtos.TrackSummaryResponse;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackService {

    private static final Logger log = LoggerFactory.getLogger(TrackService.class);

    private final TrackRepository tracks;

    public TrackService(TrackRepository tracks) {
        this.tracks = tracks;
    }

    @Transactional(readOnly = true)
    public List<TrackSummaryResponse> list(Double bpmMin, Double bpmMax, String key, int page, int size) {
        String normalizedKey = null;
        if (key != null) {
            try {
                normalizedKey = CamelotWheel.normalize(key);
            } catch (IllegalArgumentException e) {
                throw ApiException.invalid("key", e.getMessage());
            }
        }
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by("id"));
        List<TrackSummaryResponse> out = new ArrayList<>();
        for (Track t : tracks.search(bpmMin, bpmMax, normalizedKey, pageable)) {
            out.add(new TrackSummaryResponse(t.getId(), t.getTitle(), t.getArtist(), t.getDurationSeconds(), acoustic(t)));
        }
        return out;
    }

    /** PRD 7.3.2: full acoustic vector plus the RAG metadata timeline. */
    @Transactional(readOnly = true)
    public AcousticProfileResponse profile(String trackId) {
        return toProfile(find(trackId));
    }

    /** Rule-valid segue candidates for a track, ranked, with the reasons the others were rejected. */
    @Transactional(readOnly = true)
    public CompatibleTracksResponse compatible(String trackId, double tolerancePct, double sentiment, int limit) {
        Track current = find(trackId);
        List<double[]> w = SegueMatcher.bpmWindows(current.getBpm(), tolerancePct);
        double slack = 1e-6;
        List<TrackFeatures> pool = new ArrayList<>();
        for (Track t : tracks.findBpmCandidates(current.getId(),
                w.get(0)[0] - slack, w.get(0)[1] + slack,
                w.get(1)[0] - slack, w.get(1)[1] + slack,
                w.get(2)[0] - slack, w.get(2)[1] + slack)) {
            pool.add(features(t));
        }
        SegueMatcher.SegueResult result = SegueMatcher.matchSegue(
                features(current), pool, sentiment, 0.0, List.of(), SegueMatcher.DEFAULT_RECENCY_WINDOW, tolerancePct);

        List<CompatibleCandidate> ranked = new ArrayList<>();
        for (SegueMatcher.ScoredCandidate c : result.ranked()) {
            if (ranked.size() >= limit) {
                break;
            }
            ranked.add(new CompatibleCandidate(c.track().id(), c.track().title(),
                    Math.round(c.score() * 10_000) / 10_000.0, c.matchReason()));
        }
        List<RejectedCandidate> rejected = new ArrayList<>();
        for (SegueMatcher.Rejection r : result.rejected()) {
            rejected.add(new RejectedCandidate(r.trackId(), r.reasons()));
        }
        return new CompatibleTracksResponse(current.getId(), tolerancePct, ranked, rejected);
    }

    @Transactional
    public AcousticProfileResponse create(TrackCreateRequest req, String actorId) {
        String id = req.id() != null ? req.id() : Ids.newId("t");
        if (tracks.existsById(id)) {
            throw new ApiException(409, "track_exists", "A track with id " + id + " already exists.");
        }
        Track track = new Track(id, clean(req.title(), 120, "title"), clean(req.artist(), 120, "artist"),
                req.durationSeconds(), req.audioAssetUrl());
        applyAcoustic(track, req.acoustic());
        track.replaceTimeline(timeline(req.timeline()));
        tracks.saveAndFlush(track);
        log.atInfo().addKeyValue("trackId", id).addKeyValue("by", actorId).log("track.created");
        return toProfile(track);
    }

    @Transactional
    public AcousticProfileResponse updateAcoustic(String trackId, AcousticUpdateRequest req, String actorId) {
        Track track = find(trackId);
        applyAcoustic(track, req.acoustic());
        if (req.timeline() != null) { // null keeps the existing timeline; [] clears it
            track.replaceTimeline(timeline(req.timeline()));
        }
        log.atInfo().addKeyValue("trackId", trackId).addKeyValue("by", actorId).log("track.acoustic_updated");
        return toProfile(track);
    }

    // ------------------------------------------------------------------ helpers

    private Track find(String trackId) {
        return tracks.findById(trackId)
                .orElseThrow(() -> new ApiException(404, "track_not_found", "No track with id " + trackId + "."));
    }

    private static void applyAcoustic(Track track, AcousticRequest a) {
        track.setAcoustic(a.bpm(), CamelotWheel.normalize(a.camelotKey()), a.energy(), a.valence());
    }

    private static List<TrackTimelineEvent> timeline(List<TimelineEventRequest> events) {
        List<TrackTimelineEvent> out = new ArrayList<>();
        if (events != null) {
            for (int i = 0; i < events.size(); i++) {
                TimelineEventRequest e = events.get(i);
                out.add(new TrackTimelineEvent(e.ts(), e.type(), clean(e.text(), 500, "timeline[" + i + "].text")));
            }
        }
        return out;
    }

    private static String clean(String value, int max, String field) {
        try {
            return Sanitizer.sanitize(value, max, field);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid(field, e.getMessage());
        }
    }

    private static TrackFeatures features(Track t) {
        return new TrackFeatures(t.getId(), t.getBpm(), t.getCamelotKey(), t.getEnergy(), t.getValence(), t.getTitle());
    }

    private static AcousticResponse acoustic(Track t) {
        return new AcousticResponse(t.getBpm(), t.getCamelotKey(), t.getEnergy(), t.getValence());
    }

    private static AcousticProfileResponse toProfile(Track t) {
        List<TimelineEventResponse> events = new ArrayList<>();
        for (TrackTimelineEvent e : t.getTimeline()) {
            events.add(new TimelineEventResponse(e.getTsSeconds(), e.getEventType(), e.getText()));
        }
        return new AcousticProfileResponse(t.getId(), t.getTitle(), t.getArtist(), acoustic(t), events);
    }
}
