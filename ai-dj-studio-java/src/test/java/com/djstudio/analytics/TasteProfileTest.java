package com.djstudio.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TasteProfileTest {

    static TasteProfile.Entry e(double bpm, String key, double energy, double valence, double w) {
        return new TasteProfile.Entry(bpm, key, energy, valence, w);
    }

    @Test
    void emptyHistoryGivesAnEmptyProfile() {
        var p = TasteProfile.of(List.of());
        assertEquals(0, p.tracks());
        assertTrue(p.topKeys().isEmpty());
    }

    @Test
    void averagesAreWeightedByEngagement() {
        var p = TasteProfile.of(List.of(e(120, "8A", 0.8, 0.6, 3), e(100, "8A", 0.4, 0.2, 1)));
        assertEquals(115.0, p.bpmAverage(), 1e-9);               // (120*3 + 100*1) / 4
        assertEquals(0.7, p.averageEnergy(), 1e-9);
        assertEquals(0.5, p.averageValence(), 1e-9);
    }

    @Test
    void topKeysAreRankedByShareAndLimitedToThree() {
        var p = TasteProfile.of(List.of(e(120, "8A", .5, .5, 4), e(120, "9A", .5, .5, 3), e(120, "3B", .5, .5, 2),
                e(120, "1A", .5, .5, 1)));
        assertEquals(3, p.topKeys().size());
        assertEquals("8A", p.topKeys().get(0).key());
        assertEquals(0.4, p.topKeys().get(0).share(), 1e-9);
        assertEquals("9A", p.topKeys().get(1).key());
    }

    @Test
    void bpmRangeIgnoresAnOutlier() {
        var entries = new java.util.ArrayList<TasteProfile.Entry>();
        for (int i = 0; i < 18; i++) {
            entries.add(e(120 + (i % 3), "8A", .5, .5, 1));        // 120..122
        }
        entries.add(e(60, "8A", .5, .5, 1));                        // one slow outlier
        entries.add(e(180, "8A", .5, .5, 1));                       // one fast outlier
        var p = TasteProfile.of(entries);
        assertTrue(p.bpmLow() >= 120 && p.bpmHigh() <= 122, "range " + p.bpmLow() + "-" + p.bpmHigh());
    }

    @Test
    void zeroWeightEntriesAreIgnored() {
        var p = TasteProfile.of(List.of(e(100, "8A", .5, .5, 0), e(130, "9A", .5, .5, 2)));
        assertEquals(1, p.tracks());
        assertEquals(130.0, p.bpmAverage(), 1e-9);
    }
}
