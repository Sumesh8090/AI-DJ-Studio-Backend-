package com.djstudio.web;

import com.djstudio.security.UserPrincipal;
import com.djstudio.service.PersonalDashboardService;
import com.djstudio.service.RecommendationService;
import com.djstudio.web.dto.DashboardDtos.PersonalDashboard;
import com.djstudio.web.dto.RecommendationDtos.InteractionRequest;
import com.djstudio.web.dto.RecommendationDtos.RecommendationResponse;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Personalised recommendations (PRD Epic 3) and explicit interaction reporting. Always about the caller. */
@RestController
@RequestMapping("/api/v1")
public class RecommendationController {

    private final RecommendationService recommendations;
    private final PersonalDashboardService dashboard;

    public RecommendationController(RecommendationService recommendations, PersonalDashboardService dashboard) {
        this.recommendations = recommendations;
        this.dashboard = dashboard;
    }

    /** The caller's home base: lounge history, acoustic taste profile, rooms to rejoin, recommended tracks. */
    @GetMapping("/me/dashboard")
    public PersonalDashboard myDashboard(@AuthenticationPrincipal UserPrincipal user) {
        return dashboard.forUser(user.id());
    }

    @GetMapping("/recommendations")
    public RecommendationResponse recommend(@AuthenticationPrincipal UserPrincipal user,
                                            @RequestParam(defaultValue = "5") int limit) {
        if (limit < 1 || limit > 20) {
            throw ApiException.invalid("limit", "limit must be between 1 and 20.");
        }
        return recommendations.recommend(user.id(), limit);
    }

    @PostMapping("/interactions")
    public ResponseEntity<Map<String, String>> interaction(@AuthenticationPrincipal UserPrincipal user,
                                                           @Valid @RequestBody InteractionRequest req) {
        recommendations.record(user.id(), req);
        return ResponseEntity.status(201).body(Map.of("status", "recorded"));
    }
}
