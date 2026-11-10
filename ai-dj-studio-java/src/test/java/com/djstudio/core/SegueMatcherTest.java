package com.djstudio.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djstudio.core.SegueMatcher.BpmMatch;
import com.djstudio.core.SegueMatcher.BpmRelation;
import com.djstudio.core.SegueMatcher.InvalidSegueException;
import com.djstudio.core.SegueMatcher.ScoredCandidate;
import com.djstudio.core.SegueMatcher.SegueResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Acoustic matching rules (PRD 6.2), including the "at least 20 simulated segue decisions" check. */
class SegueMatcherTest {

    private static TrackFeatures t(String id, double bpm, String key, double energy) {
        return new TrackFeatures(id, bpm, key, energy);
    }

    private static final TrackFeatures CURRENT = new TrackFeatures("cur", 124, "8A", 0.78, 0.65, "Night Drive");

    private static List<String> ids(List<ScoredCandidate> ranked) {
        return ranked.stream().map(c -> c.track().id()).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ BPM rule
    @Test
    void prdExample120BpmAccepts110_4To129_6() {
        assertTrue(SegueMatcher.bpmMatch(120, 110.4).compatible());
        assertTrue(SegueMatcher.bpmMatch(120, 129.6).compatible());
        assertFalse(SegueMatcher.bpmMatch(120, 110.3).compatible());
        assertFalse(SegueMatcher.bpmMatch(120, 129.7).compatible());
    }

    @Test
    void identicalBpmHasZeroDelta() {
        BpmMatch m = SegueMatcher.bpmMatch(124, 124);
        assertTrue(m.compatible());
        assertEquals(0.0, m.deltaPct(), 1e-12);
        assertEquals(BpmRelation.DIRECT, m.relation());
    }

    @Test
    void halfAndDoubleTimeAreAccepted() {
        assertEquals(BpmRelation.HALF_TIME, SegueMatcher.bpmMatch(124, 62).relation());
        assertEquals(BpmRelation.DOUBLE_TIME, SegueMatcher.bpmMatch(124, 248).relation());
        assertTrue(SegueMatcher.bpmMatch(124, 64).compatible());   // within 8% of 62
        assertFalse(SegueMatcher.bpmMatch(124, 75).compatible());  // between the windows
    }

    @Test
    void halfTimeDeltaIsMeasuredAgainstTheScaledTarget() {
        assertEquals(2.0, SegueMatcher.bpmMatch(124, 63.24).deltaPct(), 1e-6);
    }

    @Test
    void customToleranceIsRespected() {
        assertFalse(SegueMatcher.bpmMatch(120, 126, 4).compatible());
        assertTrue(SegueMatcher.bpmMatch(120, 126, 5).compatible());
    }

    @Test
    void nonPositiveBpmThrows() {
        assertThrows(IllegalArgumentException.class, () -> SegueMatcher.bpmMatch(0, 100));
        assertThrows(IllegalArgumentException.class, () -> SegueMatcher.bpmMatch(100, 0));
        assertThrows(IllegalArgumentException.class, () -> SegueMatcher.bpmMatch(-5, 100));
    }

    @Test
    void bpmWindowsCoverThreeRanges() {
        List<double[]> w = SegueMatcher.bpmWindows(120, 8);
        assertEquals(3, w.size());
        assertEquals(110.4, w.get(0)[0], 1e-9);
        assertEquals(129.6, w.get(0)[1], 1e-9);
        assertEquals(55.2, w.get(1)[0], 1e-9);
        assertEquals(64.8, w.get(1)[1], 1e-9);
        assertEquals(220.8, w.get(2)[0], 1e-9);
        assertEquals(259.2, w.get(2)[1], 1e-9);
    }

    // ------------------------------------------------------------ track validation
    @Test
    void trackFeaturesValidateInputs() {
        assertThrows(IllegalArgumentException.class, () -> t("x", 0, "8A", 0.5));
        assertThrows(IllegalArgumentException.class, () -> t("x", 120, "13A", 0.5));
        assertThrows(IllegalArgumentException.class, () -> t("x", 120, "8A", 1.5));
        assertThrows(IllegalArgumentException.class, () -> new TrackFeatures("x", 120, "8A", 0.5, -0.1, ""));
        assertThrows(IllegalArgumentException.class, () -> t("x", Double.NaN, "8A", 0.5));
        assertEquals("8A", t("x", 120, "8a", 0.5).camelotKey());
    }

    // ---------------------------------------------------------------- segue logic
    @Test
    void selectsValidCandidatesAndRejectsClashes() {
        List<TrackFeatures> catalog = List.of(
                new TrackFeatures("t_2001", 124, "8A", 0.78, 0.65, "Night Drive"),
                new TrackFeatures("t_2002", 128, "9A", 0.84, 0.72, "Glass Harbor"),
                new TrackFeatures("t_2003", 120, "8A", 0.70, 0.60, "Paper Lanterns"),
                new TrackFeatures("t_2004", 62, "7A", 0.35, 0.50, "Low Tide Loops"),
                new TrackFeatures("t_2005", 140, "3B", 0.93, 0.41, "Static Bloom"),
                new TrackFeatures("t_2006", 126, "3A", 0.74, 0.55, "Midnight Relay"),
                new TrackFeatures("t_2007", 90, "8B", 0.45, 0.62, "Sunday Static"));

        SegueResult res = SegueMatcher.matchSegue(catalog.get(0), catalog, 0.7, 0.0, List.of(), 5, 8.0);

        assertEquals(List.of("t_2002", "t_2003", "t_2004"), ids(res.ranked()).stream().sorted().collect(Collectors.toList()));
        Map<String, List<String>> rejected = res.rejected().stream()
                .collect(Collectors.toMap(SegueMatcher.Rejection::trackId, SegueMatcher.Rejection::reasons));
        assertEquals(List.of("same_track"), rejected.get("t_2001"));
        assertEquals(List.of("bpm_out_of_range", "key_clash"), rejected.get("t_2005")); // both rules broken
        assertEquals(List.of("key_clash"), rejected.get("t_2006"));                     // tempo fine, key clashes
        assertEquals(List.of("bpm_out_of_range"), rejected.get("t_2007"));              // relative key, tempo far off

        // The PRD's required valid chain: 2001 -> 2002 -> 2003
        SegueMatcher.assertValidSegue(catalog.get(0), catalog.get(1), 8.0);
        SegueMatcher.assertValidSegue(catalog.get(1), catalog.get(2), 8.0);
    }

    @Test
    void noValidCandidatesYieldsNullSelection() {
        SegueResult res = SegueMatcher.matchSegue(CURRENT, List.of(t("c1", 200, "3B", 0.5)));
        assertNull(res.selected());
        assertTrue(res.ranked().isEmpty());
        assertEquals("c1", res.rejected().get(0).trackId());
    }

    @Test
    void currentTrackIsNeverSelected() {
        assertNull(SegueMatcher.matchSegue(CURRENT, List.of(CURRENT)).selected());
    }

    @Test
    void harmonicClosenessDominatesWhenOtherFactorsAreEqual() {
        TrackFeatures sameKey = t("a", 124, "8A", 0.5);
        TrackFeatures adjacent = t("b", 124, "9A", 0.5);
        TrackFeatures relative = t("c", 124, "8B", 0.5);
        SegueResult res = SegueMatcher.matchSegue(CURRENT, List.of(relative, adjacent, sameKey));
        assertEquals(List.of("a", "b", "c"), ids(res.ranked()));
    }

    @Test
    void sentimentSteersTowardsMatchingEnergy() {
        TrackFeatures hi = t("hi", 124, "8A", 0.95);
        TrackFeatures lo = t("lo", 124, "8A", 0.15);
        List<TrackFeatures> pool = List.of(hi, lo);
        assertEquals("hi", SegueMatcher.matchSegue(CURRENT, pool, 0.95, 0, List.of(), 5, 8).selected().track().id());
        assertEquals("lo", SegueMatcher.matchSegue(CURRENT, pool, 0.10, 0, List.of(), 5, 8).selected().track().id());
    }

    @Test
    void fallingTrendPullsTargetEnergyDownAndIsClamped() {
        assertEquals(0.1, SegueMatcher.targetEnergy(0.6, -1.0), 1e-9);
        assertEquals(1.0, SegueMatcher.targetEnergy(0.9, 1.0), 1e-9);
        assertEquals(0.0, SegueMatcher.targetEnergy(0.1, -1.0), 1e-9);
    }

    @Test
    void recentlyPlayedTrackIsDeprioritised() {
        TrackFeatures fresh = t("fresh", 124, "8A", 0.5);
        TrackFeatures stale = t("stale", 124, "8A", 0.5);
        SegueResult res = SegueMatcher.matchSegue(CURRENT, List.of(stale, fresh), 0.5, 0, List.of("stale"), 5, 8);
        assertEquals("fresh", res.selected().track().id());
    }

    @Test
    void recencyScoreScale() {
        assertEquals(1.0, SegueMatcher.recencyScore("x", List.of(), 5), 1e-12);
        assertEquals(0.0, SegueMatcher.recencyScore("x", List.of("a", "b", "x"), 5), 1e-12);        // just played
        assertEquals(2.0 / 5, SegueMatcher.recencyScore("x", List.of("x", "a", "b"), 5), 1e-12);
        assertEquals(1.0, SegueMatcher.recencyScore("x", List.of("x", "a", "b", "c", "d", "e"), 5), 1e-12); // out of window
    }

    @Test
    void rankingIsDeterministicWithTies() {
        TrackFeatures a = t("a", 124, "8A", 0.5);
        TrackFeatures b = t("b", 124, "8A", 0.5);
        assertEquals(List.of("a", "b"), ids(SegueMatcher.matchSegue(CURRENT, List.of(b, a)).ranked()));
        assertEquals(List.of("a", "b"), ids(SegueMatcher.matchSegue(CURRENT, List.of(a, b)).ranked()));
    }

    @Test
    void matchReasonShapeMatchesPrd() {
        SegueResult res = SegueMatcher.matchSegue(CURRENT, List.of(t("n", 128, "9A", 0.8)), 0.72, 0, List.of(), 5, 8);
        Map<String, Object> reason = res.selected().matchReason();
        assertEquals(3.2, reason.get("bpmDeltaPct"));
        assertEquals("adjacent", reason.get("camelotRelation"));
        assertEquals("direct", reason.get("bpmRelation"));
        assertNotNull(reason.get("energyFit"));
        assertEquals(4, reason.size());
    }

    @Test
    void weightsSumToOne() {
        assertEquals(1.0, SegueMatcher.W_HARMONIC + SegueMatcher.W_ENERGY + SegueMatcher.W_RECENCY, 1e-12);
    }

    @Test
    void invalidSentimentInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SegueMatcher.matchSegue(CURRENT, List.of(), 1.2, 0, List.of(), 5, 8));
        assertThrows(IllegalArgumentException.class, () -> SegueMatcher.matchSegue(CURRENT, List.of(), 0.5, -2, List.of(), 5, 8));
    }

    @Test
    void guardRejectsInvalidPairs() {
        assertThrows(InvalidSegueException.class, () -> SegueMatcher.assertValidSegue(CURRENT, t("far", 200, "8A", 0.5), 8));
        assertThrows(InvalidSegueException.class, () -> SegueMatcher.assertValidSegue(CURRENT, t("clash", 124, "3A", 0.5), 8));
        assertThrows(InvalidSegueException.class, () -> SegueMatcher.assertValidSegue(CURRENT, CURRENT, 8));
    }

    /** PRD 6.2 acceptance: every selected segue satisfies BPM + Camelot rules (>= 20 decisions). */
    @Test
    void hundredPercentRuleComplianceAcrossManySimulatedDecisions() {
        Random rng = new Random(1234);
        List<String> keys = new ArrayList<>(CamelotWheel.VALID_KEYS);
        double[] tolerances = {4.0, 8.0, 12.0};
        double[] factors = {1.0, 1.0, 0.5, 2.0, 1.4};
        int decisions = 0;

        for (int trial = 0; trial < 300; trial++) {
            TrackFeatures current = new TrackFeatures("cur", 60 + rng.nextDouble() * 120,
                    keys.get(rng.nextInt(keys.size())), rng.nextDouble(), rng.nextDouble(), "");
            List<TrackFeatures> pool = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                double base = current.bpm() * factors[rng.nextInt(factors.length)];
                double bpm = Math.max(30.0, base * (0.88 + rng.nextDouble() * 0.24));
                pool.add(new TrackFeatures("c" + i, bpm, keys.get(rng.nextInt(keys.size())), rng.nextDouble(), rng.nextDouble(), ""));
            }
            double tol = tolerances[rng.nextInt(tolerances.length)];
            List<String> recent = List.of("c" + rng.nextInt(25), "c" + rng.nextInt(25), "c" + rng.nextInt(25));

            SegueResult res = SegueMatcher.matchSegue(current, pool, rng.nextDouble(), rng.nextDouble() * 2 - 1, recent, 5, tol);
            if (res.selected() == null) {
                continue;
            }
            decisions++;

            // Re-derive the rules independently of the code under test, using this trial's tolerance.
            TrackFeatures sel = res.selected().track();
            double ratio = sel.bpm() / current.bpm();
            boolean tempoOk = false;
            for (double f : new double[] {1, 0.5, 2}) {
                tempoOk |= Math.abs(ratio / f - 1) * 100 <= tol + 1e-6;
            }
            assertTrue(tempoOk, "tempo rule");

            int curN = Integer.parseInt(current.camelotKey().substring(0, current.camelotKey().length() - 1));
            int selN = Integer.parseInt(sel.camelotKey().substring(0, sel.camelotKey().length() - 1));
            char curL = current.camelotKey().charAt(current.camelotKey().length() - 1);
            char selL = sel.camelotKey().charAt(sel.camelotKey().length() - 1);
            int diff = Math.floorMod(curN - selN, 12);
            boolean sameLetterNeighbour = curL == selL && (diff == 0 || diff == 1 || diff == 11);
            boolean relative = curN == selN && curL != selL;
            assertTrue(sameLetterNeighbour || relative, "key rule");

            for (ScoredCandidate c : res.ranked()) { // every ranked candidate is compliant, not just the winner
                SegueMatcher.assertValidSegue(current, c.track(), tol);
            }
        }
        assertTrue(decisions >= 20, "decisions=" + decisions);
    }
}
