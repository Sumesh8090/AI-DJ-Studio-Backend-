package com.djstudio.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SentimentTimelineTest {

    @Test
    void groupsPointsIntoAlignedBucketsAndAverages() {
        var pts = List.of(new SentimentTimeline.Point(61_000, 0.8), new SentimentTimeline.Point(65_000, 0.6),
                new SentimentTimeline.Point(5_000, 0.2), new SentimentTimeline.Point(190_000, 0.9));
        var b = SentimentTimeline.bucket(pts, 60_000);
        assertEquals(3, b.size());
        assertEquals(0, b.get(0).startMs());
        assertEquals(0.2, b.get(0).average(), 1e-9);
        assertEquals(60_000, b.get(1).startMs());
        assertEquals(0.7, b.get(1).average(), 1e-9);
        assertEquals(2, b.get(1).count());
        assertEquals(180_000, b.get(2).startMs());                // empty minutes in between are omitted
    }

    @Test
    void emptyInputGivesNeutralSummaryAndNoBuckets() {
        assertTrue(SentimentTimeline.bucket(List.of(), 1000).isEmpty());
        var s = SentimentTimeline.summarise(List.of());
        assertEquals(0, s.count());
        assertEquals(0.5, s.average(), 1e-9);
    }

    @Test
    void summaryCountsLabels() {
        var s = SentimentTimeline.summarise(List.of(new SentimentTimeline.Point(0, 0.9),
                new SentimentTimeline.Point(0, 0.8), new SentimentTimeline.Point(0, 0.5), new SentimentTimeline.Point(0, 0.1)));
        assertEquals(4, s.count());
        assertEquals(2, s.positive());
        assertEquals(1, s.neutral());
        assertEquals(1, s.negative());
        assertEquals(0.575, s.average(), 1e-9);
    }

    @Test
    void rejectsBadBucketSize() {
        assertThrows(IllegalArgumentException.class, () -> SentimentTimeline.bucket(List.of(), 0));
    }
}
