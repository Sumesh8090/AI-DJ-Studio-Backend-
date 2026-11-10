package com.djstudio.web;

import com.djstudio.core.Permission;
import com.djstudio.security.AccessGuard;
import com.djstudio.security.UserPrincipal;
import com.djstudio.service.TrackService;
import com.djstudio.web.dto.TrackDtos.AcousticProfileResponse;
import com.djstudio.web.dto.TrackDtos.AcousticUpdateRequest;
import com.djstudio.web.dto.TrackDtos.CompatibleTracksResponse;
import com.djstudio.web.dto.TrackDtos.TrackCreateRequest;
import com.djstudio.web.dto.TrackDtos.TrackSummaryResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tracks")
public class TrackController {

    private final TrackService tracks;

    public TrackController(TrackService tracks) {
        this.tracks = tracks;
    }

    @GetMapping
    public List<TrackSummaryResponse> list(
            @AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required = false) Double bpmMin,
            @RequestParam(required = false) Double bpmMax,
            @RequestParam(required = false) String key,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        AccessGuard.require(user, Permission.BROWSE_TRACKS);
        return tracks.list(bpmMin, bpmMax, key, page, size);
    }

    /** PRD 7.3.2 */
    @GetMapping("/{trackId}/acoustic-profile")
    public AcousticProfileResponse acousticProfile(@AuthenticationPrincipal UserPrincipal user,
                                                   @PathVariable String trackId) {
        AccessGuard.require(user, Permission.BROWSE_TRACKS);
        return tracks.profile(trackId);
    }

    /** Ranked rule-valid segues (BPM + Camelot) for a track, with the reasons the others were rejected. */
    @GetMapping("/{trackId}/compatible")
    public CompatibleTracksResponse compatible(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable String trackId,
            @RequestParam(defaultValue = "8.0") double tolerancePct,
            @RequestParam(defaultValue = "0.5") double sentiment,
            @RequestParam(defaultValue = "20") int limit) {
        AccessGuard.require(user, Permission.BROWSE_TRACKS);
        if (!(tolerancePct >= 1 && tolerancePct <= 20)) {
            throw ApiException.invalid("tolerancePct", "must be between 1 and 20.");
        }
        if (!(sentiment >= 0 && sentiment <= 1)) {
            throw ApiException.invalid("sentiment", "must be between 0.0 and 1.0.");
        }
        if (limit < 1 || limit > 100) {
            throw ApiException.invalid("limit", "must be between 1 and 100.");
        }
        return tracks.compatible(trackId, tolerancePct, sentiment, limit);
    }

    /** Admin upload of a track with its acoustic profile (PRD 6.3). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AcousticProfileResponse create(@AuthenticationPrincipal UserPrincipal user,
                                          @Valid @RequestBody TrackCreateRequest request) {
        AccessGuard.require(user, Permission.MANAGE_TRACKS);
        return tracks.create(request, user.id());
    }

    @PutMapping("/{trackId}/acoustic-profile")
    public AcousticProfileResponse updateAcousticProfile(@AuthenticationPrincipal UserPrincipal user,
                                                         @PathVariable String trackId,
                                                         @Valid @RequestBody AcousticUpdateRequest request) {
        AccessGuard.require(user, Permission.MANAGE_TRACKS);
        return tracks.updateAcoustic(trackId, request, user.id());
    }
}
