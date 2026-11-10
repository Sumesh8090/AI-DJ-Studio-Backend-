package com.djstudio.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djstudio.config.AppProperties;
import org.junit.jupiter.api.Test;

/** Unsafe configuration must stop the application at startup (PRD 10.1). */
class AppPropertiesTest {

    private static final String GOOD = "s".repeat(40);

    @Test
    void secretIsRequired() {
        assertThrows(IllegalStateException.class, () -> new AppProperties.Jwt(null, 15, 7));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new AppProperties.Jwt("", 15, 7));
        assertTrue(e.getMessage().contains("JWT_SECRET"));
    }

    @Test
    void shortOrPlaceholderSecretsAreRejected() {
        assertThrows(IllegalStateException.class, () -> new AppProperties.Jwt("short", 15, 7));
        assertThrows(IllegalStateException.class, () -> new AppProperties.Jwt("change-me", 15, 7));
        assertThrows(IllegalStateException.class, () -> new AppProperties.Jwt("CHANGE-ME", 15, 7));
    }

    @Test
    void accessTokenLifetimeIsCappedAtOneHour() {
        assertEquals(60, new AppProperties.Jwt(GOOD, 60, 7).accessTokenMinutes());
        assertThrows(IllegalStateException.class, () -> new AppProperties.Jwt(GOOD, 61, 7));
        assertThrows(IllegalStateException.class, () -> new AppProperties.Jwt(GOOD, 0, 7));
    }

    @Test
    void refreshLifetimeMustBePositive() {
        assertThrows(IllegalStateException.class, () -> new AppProperties.Jwt(GOOD, 15, 0));
        assertEquals(7, new AppProperties.Jwt(GOOD, 15, 7).refreshTokenDays());
    }
}
