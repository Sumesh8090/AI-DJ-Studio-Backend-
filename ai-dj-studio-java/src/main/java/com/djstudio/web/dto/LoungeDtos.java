package com.djstudio.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class LoungeDtos {

    private LoungeDtos() {
    }

    /** Request of PRD 7.3.1. Omitted optional fields take the PRD defaults (40 / 8% / true / normal). */
    public record LoungeCreateRequest(
            @NotBlank(message = "seedTrackId is required.")
            @Size(max = 64, message = "seedTrackId must be at most 64 characters.") String seedTrackId,
            @NotBlank(message = "vibeProfile is required.")
            @Size(max = 60, message = "vibeProfile must be at most 60 characters.") String vibeProfile,
            @Min(value = 2, message = "maxParticipants must be at least 2.")
            @Max(value = 100, message = "maxParticipants must be at most 100.") Integer maxParticipants,
            @DecimalMin(value = "1.0", message = "bpmTolerancePct must be between 1 and 20.")
            @DecimalMax(value = "20.0", message = "bpmTolerancePct must be between 1 and 20.") Double bpmTolerancePct,
            Boolean soundclashEnabled,
            @Pattern(regexp = "quiet|normal|chatty", message = "aiVerbosity must be quiet, normal or chatty.")
            String aiVerbosity) {
    }

    public record LoungeCreatedResponse(String loungeId, String inviteCode, String status, String hostUserId,
                                        Instant createdAt) {
    }

    public record JoinRequest(@NotBlank(message = "inviteCode is required.") String inviteCode) {
    }

    public record JoinResponse(String loungeId, String status, long participantCount, String loungeRole) {
    }

    public record QueueEntryResponse(String trackId, int position, int voteCount) {
    }

    public record LoungeResponse(String loungeId, String hostUserId, String vibeProfile, String status,
                                 boolean isLocked, String currentTrackId, List<QueueEntryResponse> queue,
                                 long participantCount, Map<String, Object> settings, Instant createdAt) {
    }
}
