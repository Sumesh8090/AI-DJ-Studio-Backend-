package com.djstudio.service;

import com.djstudio.core.Ids;
import com.djstudio.core.Sanitizer;
import com.djstudio.domain.RefreshToken;
import com.djstudio.domain.User;
import com.djstudio.repo.RefreshTokenRepository;
import com.djstudio.repo.UserRepository;
import com.djstudio.security.JwtService;
import com.djstudio.web.ApiException;
import com.djstudio.web.dto.AuthDtos.LoginRequest;
import com.djstudio.web.dto.AuthDtos.RegisterRequest;
import com.djstudio.web.dto.AuthDtos.TokenResponse;
import com.djstudio.web.dto.AuthDtos.UserResponse;
import io.jsonwebtoken.Claims;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    /** Checked against when the email is unknown, so unknown-user and wrong-password take similar time. */
    private final String dummyHash;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder encoder,
                       JwtService jwt) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.jwt = jwt;
        this.dummyHash = encoder.encode("dummy-password-for-timing");
    }

    @Transactional
    public UserResponse register(RegisterRequest req) {
        String email = req.email().strip().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            throw new ApiException(409, "email_taken", "An account with this email already exists.");
        }
        String displayName = clean(req.displayName(), 40, "displayName");
        String role = req.role() == null ? "listener" : req.role();
        User user = new User(Ids.newId("u"), email, encoder.encode(req.password()), displayName, role);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException race) { // unique(email) backstop for concurrent sign-ups
            throw new ApiException(409, "email_taken", "An account with this email already exists.");
        }
        log.atInfo().addKeyValue("userId", user.getId()).addKeyValue("role", role).log("user.registered");
        return toResponse(user);
    }

    @Transactional
    public TokenResponse login(LoginRequest req) {
        User user = users.findByEmail(req.email().strip().toLowerCase(Locale.ROOT)).orElse(null);
        boolean passwordOk = encoder.matches(req.password(), user != null ? user.getPasswordHash() : dummyHash);
        if (user == null || !passwordOk || !user.isActive()) {
            log.atInfo().log("auth.login_failed");
            throw new ApiException(401, "invalid_credentials", "Incorrect email or password.");
        }
        TokenResponse tokens = issueTokens(user);
        log.atInfo().addKeyValue("userId", user.getId()).log("auth.login");
        return tokens;
    }

    /** Refresh tokens are single-use. Presenting an already-used one revokes the user's whole token family. */
    @Transactional(noRollbackFor = ApiException.class) // the family revocation must be committed
    public TokenResponse refresh(String refreshToken) {
        Claims claims = parseRefresh(refreshToken);
        RefreshToken record = refreshTokens.findById(claims.getId())
                .orElseThrow(() -> new ApiException(401, "invalid_token", "Refresh token is not recognised."));

        if (record.isRevoked()) {
            refreshTokens.revokeAllForUser(record.getUserId());
            log.atWarn().addKeyValue("userId", record.getUserId()).log("auth.refresh_reuse_detected");
            throw new ApiException(401, "invalid_token", "Refresh token was already used. Please log in again.");
        }
        User user = users.findById(record.getUserId()).orElse(null);
        if (record.getExpiresAt().isBefore(Instant.now()) || user == null || !user.isActive()) {
            throw new ApiException(401, "invalid_token", "Refresh token has expired.");
        }
        record.setRevoked(true); // rotation
        return issueTokens(user);
    }

    @Transactional
    public void logout(String refreshToken) {
        try {
            Claims claims = jwt.parse(refreshToken, JwtService.TokenType.REFRESH);
            refreshTokens.findById(claims.getId()).ifPresent(r -> r.setRevoked(true));
        } catch (JwtService.TokenException ignored) {
            // already unusable; logout is idempotent
        }
    }

    @Transactional(readOnly = true)
    public UserResponse me(String userId) {
        return toResponse(users.findById(userId)
                .orElseThrow(() -> new ApiException(401, "invalid_token", "User no longer exists.")));
    }

    @Transactional
    public UserResponse setRole(String actingAdminId, String targetUserId, String newRole) {
        User target = users.findById(targetUserId)
                .orElseThrow(() -> new ApiException(404, "user_not_found", "No user with id " + targetUserId + "."));
        if (target.getId().equals(actingAdminId) && !"admin".equals(newRole)) {
            throw new ApiException(409, "cannot_demote_self", "Admins cannot remove their own admin role.");
        }
        target.setRole(newRole);
        log.atInfo().addKeyValue("userId", target.getId()).addKeyValue("newRole", newRole)
                .addKeyValue("changedBy", actingAdminId).log("user.role_changed");
        return toResponse(target);
    }

    // ------------------------------------------------------------------ helpers

    private TokenResponse issueTokens(User user) {
        JwtService.IssuedAccess access = jwt.createAccessToken(user.getId(), user.getRole());
        JwtService.IssuedRefresh refresh = jwt.createRefreshToken(user.getId());
        refreshTokens.save(new RefreshToken(refresh.jti(), user.getId(), refresh.expiresAt()));
        return new TokenResponse(access.token(), refresh.token(), "bearer", access.expiresInSeconds());
    }

    private Claims parseRefresh(String token) {
        try {
            return jwt.parse(token, JwtService.TokenType.REFRESH);
        } catch (JwtService.TokenException e) {
            throw new ApiException(401, "invalid_token", e.getMessage());
        }
    }

    private static String clean(String value, int max, String field) {
        try {
            return Sanitizer.sanitize(value, max, field);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid(field, e.getMessage());
        }
    }

    private static UserResponse toResponse(User u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getDisplayName(), u.getRole(), u.getCreatedAt());
    }
}
