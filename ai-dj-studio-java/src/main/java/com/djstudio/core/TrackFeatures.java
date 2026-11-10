package com.djstudio.core;

/** Acoustic feature vector for one track, validated on construction. */
public record TrackFeatures(String id, double bpm, String camelotKey, double energy, double valence, String title) {

    public TrackFeatures {
        if (!(bpm > 0) || Double.isInfinite(bpm)) {
            throw new IllegalArgumentException("Track " + id + ": BPM must be positive (got " + bpm + ").");
        }
        camelotKey = CamelotWheel.normalize(camelotKey);
        if (!(energy >= 0.0 && energy <= 1.0)) {
            throw new IllegalArgumentException("Track " + id + ": energy must be within 0.0-1.0 (got " + energy + ").");
        }
        if (!(valence >= 0.0 && valence <= 1.0)) {
            throw new IllegalArgumentException("Track " + id + ": valence must be within 0.0-1.0 (got " + valence + ").");
        }
        title = title == null ? "" : title;
    }

    public TrackFeatures(String id, double bpm, String camelotKey, double energy) {
        this(id, bpm, camelotKey, energy, 0.5, "");
    }
}
