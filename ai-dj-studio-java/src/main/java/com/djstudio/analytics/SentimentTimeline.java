package com.djstudio.analytics;

import com.djstudio.sentiment.SentimentClassifier;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Turns raw (time, score) chat sentiment into the series and totals the dashboard shows. Pure and deterministic. */
public final class SentimentTimeline {

    public record Point(long epochMs, double score) {
    }

    /** Average sentiment of the messages that fell in [startMs, startMs + bucketMs). */
    public record Bucket(long startMs, double average, int count) {
    }

    public record Summary(int count, double average, int positive, int neutral, int negative) {
    }

    private SentimentTimeline() {
    }

    /** Buckets are aligned to multiples of bucketMs; empty buckets are omitted. Output is in time order. */
    public static List<Bucket> bucket(List<Point> points, long bucketMs) {
        if (bucketMs <= 0) {
            throw new IllegalArgumentException("bucketMs must be positive.");
        }
        TreeMap<Long, double[]> acc = new TreeMap<>();     // start -> {sum, count}
        for (Point p : points) {
            long start = Math.floorDiv(p.epochMs(), bucketMs) * bucketMs;
            double[] a = acc.computeIfAbsent(start, k -> new double[2]);
            a[0] += p.score();
            a[1] += 1;
        }
        List<Bucket> out = new ArrayList<>();
        for (var e : acc.entrySet()) {
            out.add(new Bucket(e.getKey(), round(e.getValue()[0] / e.getValue()[1]), (int) e.getValue()[1]));
        }
        return out;
    }

    public static Summary summarise(List<Point> points) {
        if (points.isEmpty()) {
            return new Summary(0, 0.5, 0, 0, 0);
        }
        int pos = 0;
        int neu = 0;
        int neg = 0;
        double sum = 0;
        for (Point p : points) {
            sum += p.score();
            switch (SentimentClassifier.label(p.score())) {
                case POSITIVE -> pos++;
                case NEGATIVE -> neg++;
                default -> neu++;
            }
        }
        return new Summary(points.size(), round(sum / points.size()), pos, neu, neg);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
