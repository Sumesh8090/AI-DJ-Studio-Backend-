package com.djstudio.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djstudio.config.AppProperties;
import com.djstudio.security.JwtService.IssuedAccess;
import com.djstudio.security.JwtService.IssuedRefresh;
import com.djstudio.security.JwtService.TokenException;
import com.djstudio.security.JwtService.TokenType;
import io.jsonwebtoken.Claims;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-" + "z".repeat(32);
    private final JwtService jwt = new JwtService(new AppProperties.Jwt(SECRET, 15, 7));

    @Test
    void accessTokenCarriesClaimsAndShortTtl() {
        IssuedAccess issued = jwt.createAccessToken("u_1", "host");
        Claims claims = jwt.parse(issued.token(), TokenType.ACCESS);
        assertEquals("u_1", claims.getSubject());
        assertEquals("host", claims.get("role", String.class));
        assertEquals("access", claims.get("type", String.class));
        assertEquals(15 * 60, issued.expiresInSeconds());
        assertEquals(15 * 60 * 1000L, claims.getExpiration().getTime() - claims.getIssuedAt().getTime());
    }

    @Test
    void expiredTokenIsRejected() {
        Instant past = Instant.now().minus(3, ChronoUnit.HOURS);
        IssuedAccess issued = jwt.createAccessToken("u_1", "admin", past);
        TokenException e = assertThrows(TokenException.class, () -> jwt.parse(issued.token(), TokenType.ACCESS));
        assertTrue(e.getMessage().contains("expired"));
    }

    @Test
    void wrongSecretAndTamperingAreRejected() {
        String token = jwt.createAccessToken("u_1", "listener").token();
        JwtService other = new JwtService(new AppProperties.Jwt("another-secret-" + "q".repeat(32), 15, 7));
        assertThrows(TokenException.class, () -> other.parse(token, TokenType.ACCESS));
        String tampered = token.substring(0, token.length() - 3) + "abc";
        assertThrows(TokenException.class, () -> jwt.parse(tampered, TokenType.ACCESS));
        assertThrows(TokenException.class, () -> jwt.parse("garbage", TokenType.ACCESS));
        assertThrows(TokenException.class, () -> jwt.parse("", TokenType.ACCESS));
    }

    @Test
    void tokenTypesAreNotInterchangeable() {
        String access = jwt.createAccessToken("u_1", "listener").token();
        IssuedRefresh refresh = jwt.createRefreshToken("u_1");
        TokenException a = assertThrows(TokenException.class, () -> jwt.parse(refresh.token(), TokenType.ACCESS));
        assertTrue(a.getMessage().contains("Wrong token type"));
        assertThrows(TokenException.class, () -> jwt.parse(access, TokenType.REFRESH));
        assertEquals(refresh.jti(), jwt.parse(refresh.token(), TokenType.REFRESH).getId());
    }

    @Test
    void refreshTokensHaveUniqueIdsAndALongTtl() {
        IssuedRefresh a = jwt.createRefreshToken("u_1");
        IssuedRefresh b = jwt.createRefreshToken("u_1");
        assertNotEquals(a.jti(), b.jti());
        assertTrue(a.expiresAt().isAfter(Instant.now().plus(6, ChronoUnit.DAYS)));
    }

    @Test
    void unsignedAlgNoneTokenIsRejected() {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        long exp = Instant.now().plusSeconds(600).getEpochSecond();
        String payload = enc.encodeToString(("{\"sub\":\"u_1\",\"role\":\"admin\",\"type\":\"access\",\"jti\":\"x\","
                + "\"iat\":1,\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        assertThrows(TokenException.class, () -> jwt.parse(header + "." + payload + ".", TokenType.ACCESS));
    }
}
