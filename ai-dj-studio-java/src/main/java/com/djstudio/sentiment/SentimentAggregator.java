package com.djstudio.sentiment;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Rolling room mood in [0,1] built from chat sentiment and reactions.
 *
 * <p>Recent observations count more (exponential decay, half-life {@code halfLifeMs}) and observations older than
 * {@code windowMs} are dropped. A neutral prior of one pseudo-observation stops a single message from swinging the
 * mood. {@link #trend} is how much the mood moved over the last {@code trendMs}. Time is passed in, so it is
 * deterministic and testable. Not thread-safe (owned by one room).
 */
public final class SentimentAggregator {

    private record Obs(long ts, double score) {
    }

    private static final double PRIOR_WEIGHT = 1.0;
    private static final double NEUTRAL = 0.5;

    private final long windowMs;
    private final long halfLifeMs;
    private final long trendMs;
    private final Deque<Obs> obs = new ArrayDeque<>();

    public SentimentAggregator(long windowMs, long halfLifeMs, long trendMs) {
        this.windowMs = windowMs;
        this.halfLifeMs = halfLifeMs;
        this.trendMs = trendMs;
    }

    public static SentimentAggregator standard() {
        return new SentimentAggregator(120_000, 30_000, 30_000);
    }

    public void add(long ts, double score) {
        double s = Math.max(0.0, Math.min(1.0, score));
        obs.addLast(new Obs(ts, s));
        while (!obs.isEmpty() && ts - obs.peekFirst().ts() > windowMs) {
            obs.pollFirst();
        }
    }

    public int count() {
        return obs.size();
    }

    /** Current mood at {@code now}; 0.5 (neutral) when there is no recent data. */
    public double current(long now) {
        return valueAt(now);
    }

    /** Change of mood over the trend window, in [-1, 1]. Positive = getting happier. */
    public double trend(long now) {
        double t = valueAt(now) - valueAt(now - trendMs);
        return Math.max(-1.0, Math.min(1.0, t));
    }

    private double valueAt(long t) {
        double sum = NEUTRAL * PRIOR_WEIGHT;
        double weights = PRIOR_WEIGHT;
        for (Obs o : obs) {
            if (o.ts() > t || t - o.ts() > windowMs) {
                continue;
            }
            double w = Math.pow(0.5, (double) (t - o.ts()) / halfLifeMs);
            sum += w * o.score();
            weights += w;
        }
        return sum / weights;
    }
}
