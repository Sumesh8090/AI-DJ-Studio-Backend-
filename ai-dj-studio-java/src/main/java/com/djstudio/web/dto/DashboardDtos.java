package com.djstudio.web.dto;

import com.djstudio.analytics.TasteProfile;
import com.djstudio.web.dto.RecommendationDtos.RecommendedTrack;
import java.time.Instant;
import java.util.List;

public final class DashboardDtos {

    private DashboardDtos() {
    }

    /** One lounge the user hosted or attended. role is the user's role in that lounge. */
    public record LoungeHistoryItem(String loungeId, String vibeProfile, String status, String role, boolean hosted,
                                    Instant joinedAt, Instant createdAt, Instant endedAt) {
    }

    /** A live or scheduled lounge the user can jump back into (quick rejoin). */
    public record ActiveRoom(String loungeId, String vibeProfile, String status, String role, long onlineNow,
                             String websocketPath) {
    }

    public record PersonalDashboard(String userId, List<LoungeHistoryItem> lounges, List<ActiveRoom> activeRooms,
                                    TasteProfile.Profile tasteProfile, List<RecommendedTrack> recommendations,
                                    Instant generatedAt) {
    }
}
