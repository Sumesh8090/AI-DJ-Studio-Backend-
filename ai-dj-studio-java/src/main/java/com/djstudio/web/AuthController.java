package com.djstudio.web;

import com.djstudio.core.Permission;
import com.djstudio.security.AccessGuard;
import com.djstudio.security.UserPrincipal;
import com.djstudio.service.AuthService;
import com.djstudio.web.dto.AuthDtos.LoginRequest;
import com.djstudio.web.dto.AuthDtos.RefreshRequest;
import com.djstudio.web.dto.AuthDtos.RegisterRequest;
import com.djstudio.web.dto.AuthDtos.RoleUpdateRequest;
import com.djstudio.web.dto.AuthDtos.TokenResponse;
import com.djstudio.web.dto.AuthDtos.UserResponse;
import com.djstudio.security.LoginThrottle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthService auth;
    private final LoginThrottle throttle;

    public AuthController(AuthService auth, LoginThrottle throttle) {
        this.auth = auth;
        this.throttle = throttle;
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return auth.register(request);
    }

    @PostMapping("/auth/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String address = http.getRemoteAddr();
        throttle.check(request.email(), address);
        try {
            TokenResponse tokens = auth.login(request);
            throttle.succeeded(request.email(), address);
            return tokens;
        } catch (ApiException e) {
            if (e.status() == 401) {
                throttle.failed(request.email(), address);
            }
            throw e;
        }
    }

    @PostMapping("/auth/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return auth.refresh(request.refreshToken());
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        auth.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/auth/me")
    public UserResponse me(@AuthenticationPrincipal UserPrincipal user) {
        return auth.me(user.id());
    }

    /** Admin only: grant or change a user's role (co_dj and admin cannot be self-assigned at registration). */
    @PatchMapping("/admin/users/{userId}/role")
    public UserResponse setRole(@AuthenticationPrincipal UserPrincipal admin, @PathVariable String userId,
                                @Valid @RequestBody RoleUpdateRequest request) {
        AccessGuard.require(admin, Permission.MANAGE_USERS);
        return auth.setRole(admin.id(), userId, request.role());
    }
}
