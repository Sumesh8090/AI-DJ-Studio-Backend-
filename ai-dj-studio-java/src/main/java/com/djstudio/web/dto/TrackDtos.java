package com.djstudio.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public final class TrackDtos {

    private TrackDtos() {
    }

    // ------------------------------------------------------------ requests

    /** Acoustic feature vector. Validation here is the admin-upload contract from PRD 6.3. */
    public record AcousticRequest(
            @NotNull(message = "BPM is required.")
            @DecimalMin(value = "0.0", inclusive = false, message = "BPM must be a positive number.")
            @DecimalMax(value = "300", message = "BPM must be at most 300.") Double bpm,
            @NotNull(message = "Camelot key is required.") @CamelotKey String camelotKey,
            @NotNull(message = "Energy is required.")
            @DecimalMin(value = "0.0", message = "Energy must be between 0.0 and 1.0.")
            @DecimalMax(value = "1.0", message = "Energy must be between 0.0 and 1.0.") Double energy,
            @NotNull(message = "Valence is required.")
            @DecimalMin(value = "0.0", message = "Valence must be between 0.0 and 1.0.")
            @DecimalMax(value = "1.0", message = "Valence must be between 0.0 and 1.0.") Double valence) {
    }

    public record TimelineEventRequest(
            @NotNull(message = "ts is required.")
            @Min(value = 0, message = "ts must be >= 0 seconds.")
            @Max(value = 36000, message = "ts must be <= 36000 seconds.") Integer ts,
            @NotNull(message = "type is required.")
            @Pattern(regexp = "sample_credit|trivia|glossary", message = "type must be sample_credit, trivia or glossary.")
            String type,
            @NotBlank(message = "text is required.")
            @Size(max = 500, message = "text must be at most 500 characters.") String text) {
    }

    public record TrackCreateRequest(
            @Pattern(regexp = "^t_[A-Za-z0-9]{1,32}$", message = "id must look like t_1234 (letters and digits).") String id,
            @NotBlank(message = "title is required.")
            @Size(max = 120, message = "title must be at most 120 characters.") String title,
            @NotBlank(message = "artist is required.")
            @Size(max = 120, message = "artist must be at most 120 characters.") String artist,
            @NotNull(message = "durationSeconds is required.")
            @Min(value = 1, message = "durationSeconds must be positive.")
            @Max(value = 36000, message = "durationSeconds must be at most 36000.") Integer durationSeconds,
            @Size(max = 500, message = "audioAssetUrl must be at most 500 characters.")
            @Pattern(regexp = "^(https?://|/)[^\\s<>\"']+$",
                    message = "audioAssetUrl must be an http(s) URL or an absolute path like /media/track.mp3.")
            String audioAssetUrl,
            @NotNull(message = "acoustic is required.") @Valid AcousticRequest acoustic,
            @Size(max = 200, message = "timeline may have at most 200 entries.") List<@Valid TimelineEventRequest> timeline) {
    }

    /** timeline == null keeps the existing timeline; an empty list clears it. */
    public record AcousticUpdateRequest(
            @NotNull(message = "acoustic is required.") @Valid AcousticRequest acoustic,
            @Size(max = 200, message = "timeline may have at most 200 entries.") List<@Valid TimelineEventRequest> timeline) {
    }

    // ----------------------------------------------------------- responses

    public record AcousticResponse(double bpm, String camelotKey, double energy, double valence) {
    }

    public record TimelineEventResponse(int ts, String type, String text) {
    }

    /** Shape of PRD 7.3.2. */
    public record AcousticProfileResponse(String trackId, String title, String artist, AcousticResponse acoustic,
                                          List<TimelineEventResponse> timeline) {
    }

    public record TrackSummaryResponse(String id, String title, String artist, int durationSeconds,
                                       AcousticResponse acoustic) {
    }

    public record CompatibleCandidate(String trackId, String title, double score, Map<String, Object> matchReason) {
    }

    public record RejectedCandidate(String trackId, List<String> reasons) {
    }

    public record CompatibleTracksResponse(String currentTrackId, double tolerancePct,
                                           List<CompatibleCandidate> ranked, List<RejectedCandidate> rejected) {
    }
}
