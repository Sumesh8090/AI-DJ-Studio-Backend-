package com.djstudio.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AiDtos {

    private AiDtos() {
    }

    /** Request of PRD 7.3.3. currentTs is the playback position in seconds (informational for now). */
    public record SegueRequest(
            @NotBlank(message = "currentTrackId is required.")
            @Size(max = 64, message = "currentTrackId must be at most 64 characters.") String currentTrackId,
            Double currentTs,
            @NotEmpty(message = "candidateTrackIds must contain at least one track.")
            @Size(max = 20, message = "candidateTrackIds must contain at most 20 tracks.")
            List<@NotBlank(message = "candidateTrackIds must not contain blanks.") String> candidateTrackIds,
            @DecimalMin(value = "0.0", message = "currentSentimentScore must be between 0.0 and 1.0.")
            @DecimalMax(value = "1.0", message = "currentSentimentScore must be between 0.0 and 1.0.")
            Double currentSentimentScore) {
    }

    public record SegueResponse(String selectedTrackId, Map<String, Object> matchReason, String transitionScript,
                                List<String> sourceEvents, Instant generatedAt) {
    }

    public record AskRequest(
            @NotBlank(message = "question is required.")
            @Size(max = 300, message = "question must be at most 300 characters.") String question,
            @Size(max = 64, message = "trackId must be at most 64 characters.") String trackId) {
    }

    public record AskResponse(String answer, boolean grounded, List<String> citations, Instant generatedAt) {
    }
}
