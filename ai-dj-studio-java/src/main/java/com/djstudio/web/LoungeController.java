package com.djstudio.web;

import com.djstudio.core.Permission;
import com.djstudio.security.AccessGuard;
import com.djstudio.security.UserPrincipal;
import com.djstudio.service.LoungeService;
import com.djstudio.web.dto.LoungeDtos.JoinRequest;
import com.djstudio.web.dto.LoungeDtos.JoinResponse;
import com.djstudio.web.dto.LoungeDtos.LoungeCreateRequest;
import com.djstudio.web.dto.LoungeDtos.LoungeCreatedResponse;
import com.djstudio.web.dto.LoungeDtos.LoungeResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lounges")
public class LoungeController {

    private final LoungeService lounges;

    public LoungeController(LoungeService lounges) {
        this.lounges = lounges;
    }

    /** PRD 7.3.1 - Host (or Admin) creates a lounge. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LoungeCreatedResponse create(@AuthenticationPrincipal UserPrincipal user,
                                        @Valid @RequestBody LoungeCreateRequest request) {
        AccessGuard.require(user, Permission.CREATE_LOUNGE);
        return lounges.create(user, request);
    }

    @PostMapping("/join")
    public JoinResponse join(@AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody JoinRequest request) {
        AccessGuard.require(user, Permission.JOIN_LOUNGE);
        return lounges.join(user, request.inviteCode());
    }

    @GetMapping("/{loungeId}")
    public LoungeResponse get(@AuthenticationPrincipal UserPrincipal user, @PathVariable String loungeId) {
        return lounges.get(user, loungeId);
    }
}
