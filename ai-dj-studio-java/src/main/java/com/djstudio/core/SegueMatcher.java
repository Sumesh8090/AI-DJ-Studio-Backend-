package com.djstudio.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Acoustic feature matcher: deterministic, framework-free segue selection (PRD 6.2).
 *
 * <p>Hard constraints (never relaxed, enforced in code):
 * <ol>
 *   <li>BPM within +/- tolerance (default 8%) of the current track, OR of its half-time (x0.5) /
 *       double-time (x2) multiple.</li>
 *   <li>Camelot key identical, adjacent on the wheel, or relative major/minor.</li>
 * </ol>
 * Candidates that pass both are ranked: 50% harmonic closeness + 30% energy-curve fit against room
 * sentiment + 20% recency.
 */
public final class SegueMatcher {

    public static final double W_HARMONIC = 0.50;
    public static final double W_ENERGY = 0.30;
    public static final double W_RECENCY = 0.20;

    public static final double DEFAULT_TOLERANCE_PCT = 8.0;
    public static final int DEFAULT_RECENCY_WINDOW = 5;

    /** Float slack so exact-boundary values (e.g. 129.6 vs 120) are accepted. */
    private static final double EPS = 1e-9;

    public static final String REASON_BPM = "bpm_out_of_range";
    public static final String REASON_KEY = "key_clash";
    public static final String REASON_SAME_TRACK = "same_track";

    private SegueMatcher() {
    }

    public enum BpmRelation {
        DIRECT("direct"), HALF_TIME("half_time"), DOUBLE_TIME("double_time"), NONE("none");

        private final String wire;

        BpmRelation(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /** deltaPct is the distance from the matched target tempo, in percent. */
    public record BpmMatch(boolean compatible, double deltaPct, BpmRelation relation) {
    }

    public record ScoredCandidate(
            TrackFeatures track,
            BpmMatch bpmMatch,
            CamelotWheel.Relation camelotRelation,
            double harmonicScore,
            double energyFit,
            double recencyScore,
            double score) {

        /** Payload shaped like the matchReason object in PRD 7.3.3. */
        public Map<String, Object> matchReason() {
            Map<String, Object> reason = new LinkedHashMap<>();
            reason.put("bpmDeltaPct", round(bpmMatch.deltaPct(), 1));
            reason.put("bpmRelation", bpmMatch.relation().wire());
            reason.put("camelotRelation", camelotRelation.wire());
            reason.put("energyFit", round(energyFit, 2));
            return reason;
        }
    }

    public record Rejection(String trackId, List<String> reasons) {
    }

    /** selected is null when no candidate satisfies both hard rules. */
    public record SegueResult(ScoredCandidate selected, List<ScoredCandidate> ranked, List<Rejection> rejected) {
    }

    public static class InvalidSegueException extends IllegalArgumentException {
        public InvalidSegueException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ BPM

    /** Inclusive [low, high] BPM ranges a candidate may fall in; used for indexed SQL pre-filtering. */
    public static List<double[]> bpmWindows(double currentBpm, double tolerancePct) {
        List<double[]> windows = new ArrayList<>(3);
        for (double factor : new double[] {1.0, 0.5, 2.0}) {
            double target = currentBpm * factor;
            double spread = target * tolerancePct / 100.0;
            windows.add(new double[] {target - spread, target + spread});
        }
        return windows;
    }

    public static BpmMatch bpmMatch(double currentBpm, double candidateBpm, double tolerancePct) {
        if (currentBpm <= 0 || candidateBpm <= 0) {
            throw new IllegalArgumentException("BPM values must be positive.");
        }
        if (tolerancePct < 0) {
            throw new IllegalArgumentException("Tolerance must be non-negative.");
        }
        BpmMatch best = null;
        BpmRelation[] relations = {BpmRelation.DIRECT, BpmRelation.HALF_TIME, BpmRelation.DOUBLE_TIME};
        double[] factors = {1.0, 0.5, 2.0};
        for (int i = 0; i < relations.length; i++) {
            double target = currentBpm * factors[i];
            double delta = Math.abs(candidateBpm - target) / target * 100.0;
            if (delta <= tolerancePct + EPS && (best == null || delta < best.deltaPct())) {
                best = new BpmMatch(true, delta, relations[i]);
            }
        }
        if (best != null) {
            return best;
        }
        return new BpmMatch(false, Math.abs(candidateBpm - currentBpm) / currentBpm * 100.0, BpmRelation.NONE);
    }

    public static BpmMatch bpmMatch(double currentBpm, double candidateBpm) {
        return bpmMatch(currentBpm, candidateBpm, DEFAULT_TOLERANCE_PCT);
    }

    // ------------------------------------------------------------ hard guard

    /** Defence-in-depth: throws if the pair violates either hard constraint. */
    public static void assertValidSegue(TrackFeatures current, TrackFeatures candidate, double tolerancePct) {
        if (current.id().equals(candidate.id())) {
            throw new InvalidSegueException("A track cannot segue into itself.");
        }
        if (!bpmMatch(current.bpm(), candidate.bpm(), tolerancePct).compatible()) {
            throw new InvalidSegueException("BPM " + candidate.bpm() + " is outside +/-" + tolerancePct
                    + "% of " + current.bpm() + " (or its half/double time).");
        }
        if (CamelotWheel.relation(current.camelotKey(), candidate.camelotKey()).isEmpty()) {
            throw new InvalidSegueException("Keys " + current.camelotKey() + " -> " + candidate.camelotKey()
                    + " clash on the Camelot wheel.");
        }
    }

    // --------------------------------------------------------------- scoring

    /** Energy the room currently wants. sentiment is 0..1; trend is -1..1 (falling..rising). */
    public static double targetEnergy(double sentiment, double sentimentTrend) {
        return Math.min(1.0, Math.max(0.0, sentiment + 0.5 * sentimentTrend));
    }

    public static double energyFit(double candidateEnergy, double desiredEnergy) {
        return 1.0 - Math.abs(candidateEnergy - desiredEnergy);
    }

    /**
     * 1.0 if not played in the last {@code window} tracks; otherwise grows with how long ago it played.
     * {@code recentTrackIds} is ordered oldest to newest.
     */
    public static double recencyScore(String trackId, List<String> recentTrackIds, int window) {
        if (window <= 0) {
            return 1.0;
        }
        int from = Math.max(0, recentTrackIds.size() - window);
        List<String> tail = recentTrackIds.subList(from, recentTrackIds.size());
        int idx = tail.lastIndexOf(trackId);
        if (idx < 0) {
            return 1.0;
        }
        int playsAgo = tail.size() - 1 - idx; // 0 == most recent
        return playsAgo / (double) window;
    }

    // --------------------------------------------------------------- matching

    public static SegueResult matchSegue(
            TrackFeatures current,
            Collection<TrackFeatures> candidates,
            double sentiment,
            double sentimentTrend,
            List<String> recentTrackIds,
            int recencyWindow,
            double bpmTolerancePct) {

        if (!(sentiment >= 0.0 && sentiment <= 1.0)) {
            throw new IllegalArgumentException("sentiment must be within 0.0-1.0 (got " + sentiment + ").");
        }
        if (!(sentimentTrend >= -1.0 && sentimentTrend <= 1.0)) {
            throw new IllegalArgumentException("sentimentTrend must be within -1.0-1.0 (got " + sentimentTrend + ").");
        }

        double desired = targetEnergy(sentiment, sentimentTrend);
        List<ScoredCandidate> ranked = new ArrayList<>();
        List<Rejection> rejected = new ArrayList<>();

        for (TrackFeatures cand : candidates) {
            if (cand.id().equals(current.id())) {
                rejected.add(new Rejection(cand.id(), List.of(REASON_SAME_TRACK)));
                continue;
            }
            BpmMatch bpm = bpmMatch(current.bpm(), cand.bpm(), bpmTolerancePct);
            var relation = CamelotWheel.relation(current.camelotKey(), cand.camelotKey());

            List<String> reasons = new ArrayList<>(2);
            if (!bpm.compatible()) {
                reasons.add(REASON_BPM);
            }
            if (relation.isEmpty()) {
                reasons.add(REASON_KEY);
            }
            if (!reasons.isEmpty()) {
                rejected.add(new Rejection(cand.id(), List.copyOf(reasons)));
                continue;
            }

            double harmonic = relation.get().closeness();
            double fit = energyFit(cand.energy(), desired);
            double recency = recencyScore(cand.id(), recentTrackIds, recencyWindow);
            double score = W_HARMONIC * harmonic + W_ENERGY * fit + W_RECENCY * recency;
            ranked.add(new ScoredCandidate(cand, bpm, relation.get(), harmonic, fit, recency, score));
        }

        // Highest score first; track id is a stable tie-break so results are reproducible.
        ranked.sort(Comparator.comparingDouble((ScoredCandidate c) -> -round(c.score(), 12))
                .thenComparing(c -> c.track().id()));

        ScoredCandidate selected = ranked.isEmpty() ? null : ranked.get(0);
        if (selected != null) {
            assertValidSegue(current, selected.track(), bpmTolerancePct); // hard-constraint backstop
        }
        return new SegueResult(selected, List.copyOf(ranked), List.copyOf(rejected));
    }

    /** Convenience overload: neutral sentiment, no history, default tolerance. */
    public static SegueResult matchSegue(TrackFeatures current, Collection<TrackFeatures> candidates) {
        return matchSegue(current, candidates, 0.5, 0.0, List.of(), DEFAULT_RECENCY_WINDOW, DEFAULT_TOLERANCE_PCT);
    }

    static double round(double value, int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }
}
