package com.djstudio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Server-side record of issued refresh tokens: enables rotation, logout and reuse detection. */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @Column(length = 64)
    private String jti;

    @Column(name = "user_id", nullable = false, length = 32)
    private String userId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected RefreshToken() {
    }

    public RefreshToken(String jti, String userId, Instant expiresAt) {
        this.jti = jti;
        this.userId = userId;
        this.expiresAt = expiresAt;
    }

    public String getJti() { return jti; }
    public String getUserId() { return userId; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
}
