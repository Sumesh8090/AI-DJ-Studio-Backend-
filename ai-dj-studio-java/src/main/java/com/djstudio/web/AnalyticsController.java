package com.djstudio.web;

import com.djstudio.core.Permission;
import com.djstudio.core.Role;
import com.djstudio.domain.DjLounge;
import com.djstudio.domain.LoungeParticipant;
import com.djstudio.repo.DjLoungeRepository;
import com.djstudio.repo.LoungeParticipantRepository;
import com.djstudio.security.AccessGuard;
import com.djstudio.security.UserPrincipal;
import com.djstudio.service.AnalyticsService;
import com.djstudio.web.dto.AnalyticsDtos.LoungeAnalytics;
import com.djstudio.web.dto.AnalyticsDtos.Overview;
import com.djstudio.ws.RoomService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard data (PRD Epic 3). Lounge analytics are for that lounge's DJs and admins; the overview is admin-only. */
@RestController
@RequestMapping("/api/v1")
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final DjLoungeRepository lounges;
    private final LoungeParticipantRepository participants;
    private final RoomService rooms;

    public AnalyticsController(AnalyticsService analytics, DjLoungeRepository lounges,
                               LoungeParticipantRepository participants, RoomService rooms) {
        this.analytics = analytics;
        this.lounges = lounges;
        this.participants = participants;
        this.rooms = rooms;
    }

    @GetMapping("/lounges/{loungeId}/analytics")
    public LoungeAnalytics lounge(@AuthenticationPrincipal UserPrincipal user, @PathVariable String loungeId,
                                  @RequestParam(defaultValue = "60") int bucketSeconds) {
        DjLounge lounge = lounges.findById(loungeId)
                .orElseThrow(() -> new ApiException(404, "lounge_not_found", "No lounge with id " + loungeId + "."));
        Role role = "admin".equals(user.role()) ? Role.ADMIN
                : participants.findByLoungeIdAndUserId(loungeId, user.id())
                        .filter(p -> p.getLeftAt() == null).map(LoungeParticipant::getLoungeRole)
                        .flatMap(Role::fromValue).orElse(null);
        if (role == null) {
            throw new ApiException(403, "not_a_participant", "Join this lounge first.");
        }
        if (!Permission.OVERRIDE_QUEUE.allows(role.value())) {
            throw new ApiException(403, "forbidden", "Only a co-DJ, host or admin can view lounge analytics.");
        }
        if (bucketSeconds < 10 || bucketSeconds > 3600) {
            throw ApiException.invalid("bucketSeconds", "bucketSeconds must be between 10 and 3600.");
        }
        return analytics.lounge(loungeId, lounge.getStatus(), lounge.getEndedAt(), rooms.onlineCount(loungeId),
                bucketSeconds * 1000L);
    }

    @GetMapping("/analytics/overview")
    public Overview overview(@AuthenticationPrincipal UserPrincipal user) {
        AccessGuard.require(user, Permission.MANAGE_USERS);
        return analytics.overview();
    }
}
