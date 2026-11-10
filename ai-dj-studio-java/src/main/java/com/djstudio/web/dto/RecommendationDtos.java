package com.djstudio.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class RecommendationDtos {

    private RecommendationDtos() {
    }

    public record RecommendedTrack(String trackId, String title, String artist, double score, String source,
                                   double bpm, String camelotKey) {
    }

    public record RecommendationResponse(String userId, List<RecommendedTrack> tracks, Instant generatedAt) {
    }

    /** A client-reported interaction, for example a manual like outside a live lounge. */
    public record InteractionRequest(
            @NotBlank(message = "trackId is required.")
            @Size(max = 64, message = "trackId must be at most 64 characters.") String trackId,
            @Min(value = 0, message = "playedSeconds must be zero or more.") Integer playedSeconds,
            Boolean completed,
            Boolean liked) {
    }
}
