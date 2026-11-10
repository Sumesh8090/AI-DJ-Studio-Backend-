package com.djstudio.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Environment-driven configuration. No secrets live in source control (PRD 10.1). */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Seed seed) {

    public static final int MIN_SECRET_LENGTH = 32;
    public static final int MAX_ACCESS_TOKEN_MINUTES = 60; // PRD 10.1: tokens must be short-lived (<= 1 hour)
    private static final Set<String> PLACEHOLDERS = Set.of("change-me", "changeme", "secret", "your-secret-here", "replace-me");

    /** Validated on construction, so a bad config stops the application at startup with a clear message. */
    public record Jwt(String secret, int accessTokenMinutes, int refreshTokenDays) {
        public Jwt {
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException("JWT_SECRET is required (set it in your environment or .env file).");
            }
            if (PLACEHOLDERS.contains(secret.toLowerCase()) || secret.length() < MIN_SECRET_LENGTH) {
                throw new IllegalStateException("JWT_SECRET must be a non-placeholder value of at least "
                        + MIN_SECRET_LENGTH + " characters. Generate one with: openssl rand -base64 48");
            }
            if (accessTokenMinutes < 1 || accessTokenMinutes > MAX_ACCESS_TOKEN_MINUTES) {
                throw new IllegalStateException("ACCESS_TOKEN_MINUTES must be between 1 and " + MAX_ACCESS_TOKEN_MINUTES + ".");
            }
            if (refreshTokenDays < 1) {
                throw new IllegalStateException("REFRESH_TOKEN_DAYS must be >= 1.");
            }
        }
    }

    public record Seed(boolean enabled, String demoPassword) {
    }
}
