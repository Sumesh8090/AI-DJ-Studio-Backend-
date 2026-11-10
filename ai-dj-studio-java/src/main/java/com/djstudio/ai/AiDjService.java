package com.djstudio.ai;

import com.djstudio.core.SegueMatcher;
import com.djstudio.core.TrackFeatures;
import com.djstudio.domain.Track;
import com.djstudio.domain.TrackTimelineEvent;
import com.djstudio.repo.TrackRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Glue between the database and the pure AI classes: loads tracks, runs the acoustic matcher (hard BPM/Camelot rules
 * plus the 50/30/20 ranking), then writes a grounded transition script. Read-only: it never changes room state.
 */
@Service
public class AiDjService {

    private static final Logger log = LoggerFactory.getLogger(AiDjService.class);
    private static final int RECENCY_WINDOW = SegueMatcher.DEFAULT_RECENCY_WINDOW;

    public record Proposal(String selectedTrackId, String transitionScript, Map<String, Object> matchReason,
                           List<String> sourceEvents, Instant generatedAt) {
    }

    private final TrackRepository tracks;
    private final TransitionWriter writer = new TransitionWriter();
    private final Retriever retriever = new Retriever();

    public AiDjService(TrackRepository tracks) {
        this.tracks = tracks;
    }

    /**
     * @return the best compatible candidate with its script, or empty when the current track is unknown or no
     *         candidate satisfies both hard rules (the caller reports that; it must never force a bad segue).
     */
    @Transactional(readOnly = true)
    public Optional<Proposal> propose(String currentTrackId, List<String> candidateIds, List<String> recentIds,
                                      double sentiment, double sentimentTrend, double tolerancePct,
                                      TransitionWriter.Verbosity verbosity) {
        long started = System.nanoTime();
        Set<String> wanted = new LinkedHashSet<>(candidateIds);
        wanted.add(currentTrackId);
        Map<String, Track> byId = tracks.findAllById(wanted).stream()
                .collect(Collectors.toMap(Track::getId, Function.identity()));
        Track current = byId.get(currentTrackId);
        if (current == null) {
            return Optional.empty();
        }
        List<TrackFeatures> candidates = new ArrayList<>();
        for (String id : candidateIds) {
            Track t = byId.get(id);
            if (t != null && !id.equals(currentTrackId)) {
                candidates.add(features(t));
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        double s = Math.max(0.0, Math.min(1.0, sentiment));
        double trend = Math.max(-1.0, Math.min(1.0, sentimentTrend));
        SegueMatcher.SegueResult result = SegueMatcher.matchSegue(features(current), candidates, s, trend,
                recentIds, RECENCY_WINDOW, tolerancePct);
        if (result.selected() == null) {
            return Optional.empty();
        }
        Track chosen = byId.get(result.selected().track().id());
        Map<String, Object> reason = result.selected().matchReason();
        TransitionWriter.Script script = writer.write(meta(current), meta(chosen), reason, verbosity);
        log.atInfo().addKeyValue("currentTrackId", currentTrackId)
                .addKeyValue("selectedTrackId", chosen.getId())
                .addKeyValue("candidates", candidates.size())
                .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000)
                .log("ai.segue.proposed");
        return Optional.of(new Proposal(chosen.getId(), script.text(), reason, script.sourceEvents(), Instant.now()));
    }

    /** Grounded Q&A about the current track (or one named in the question) from the given pool of tracks. */
    @Transactional(readOnly = true)
    public Retriever.Answer ask(String question, String currentTrackId, List<String> poolIds) {
        Set<String> wanted = new LinkedHashSet<>(poolIds);
        if (currentTrackId != null) {
            wanted.add(currentTrackId);
        }
        Map<String, Track> byId = tracks.findAllById(wanted).stream()
                .collect(Collectors.toMap(Track::getId, Function.identity()));
        List<TrackMeta> pool = new ArrayList<>();
        for (String id : wanted) {
            Track t = byId.get(id);
            if (t != null) {
                pool.add(meta(t));
            }
        }
        TrackMeta current = currentTrackId == null || !byId.containsKey(currentTrackId) ? null : meta(byId.get(currentTrackId));
        if (current == null) {
            return new Retriever.Answer(Retriever.DECLINE, false, List.of());
        }
        return retriever.answer(question, current, pool);
    }

    /** Does a segue from {@code currentId} to {@code candidateId} satisfy both hard rules (BPM and Camelot key)? */
    @Transactional(readOnly = true)
    public boolean isCompatible(String currentId, String candidateId, double tolerancePct) {
        Optional<Track> cur = tracks.findById(currentId);
        Optional<Track> cand = tracks.findById(candidateId);
        if (cur.isEmpty() || cand.isEmpty()) {
            return false;
        }
        try {
            SegueMatcher.assertValidSegue(features(cur.get()), features(cand.get()), tolerancePct);
            return true;
        } catch (SegueMatcher.InvalidSegueException e) {
            return false;
        }
    }

    private static TrackFeatures features(Track t) {
        return new TrackFeatures(t.getId(), t.getBpm(), t.getCamelotKey(), t.getEnergy(), t.getValence(), t.getTitle());
    }

    private static TrackMeta meta(Track t) {
        List<MetaEntry> entries = new ArrayList<>();
        for (TrackTimelineEvent e : t.getTimeline()) {
            entries.add(new MetaEntry(String.valueOf(e.getId()), e.getEventType(), e.getText(), e.getTsSeconds()));
        }
        return new TrackMeta(t.getId(), t.getTitle(), t.getBpm(), t.getCamelotKey(), t.getEnergy(), entries);
    }
}
