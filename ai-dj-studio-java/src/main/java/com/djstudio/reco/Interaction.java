package com.djstudio.reco;

/** One listener's interaction with one track (a row of the interactions table). */
public record Interaction(String userId, String trackId, int playedSeconds, boolean completed, boolean liked) {

    /** Implicit-feedback strength: a like says most, finishing a track says some, a brief play says little. */
    public double weight() {
        if (liked) {
            return 3.0;
        }
        if (completed) {
            return 1.5;
        }
        return playedSeconds >= 30 ? 0.75 : 0.25;
    }
}
