package com.djstudio.security;

import com.djstudio.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Issues and verifies HS256 JWTs. Access and refresh tokens are not interchangeable (the "type" claim). */
@Service
public class JwtService {

    public enum TokenType {
        ACCESS("access"), REFRESH("refresh");

        private final String wire;

        TokenType(String wire) {
            this.wire = wire;
        }
    }

    public record IssuedAccess(String token, long expiresInSeconds) {
    }

    /** The jti is persisted so refresh tokens can be rotated and revoked. */
    public record IssuedRefresh(String token, String jti, Instant expiresAt) {
    }

    /** Token is missing, malformed, expired, or of the wrong type. */
    public static class TokenException extends RuntimeException {
        public TokenException(String message) {
            super(message);
        }
    }

    private final SecretKey key;
    private final AppProperties.Jwt config;

    @Autowired
    public JwtService(AppProperties properties) {
        this(properties.jwt());
    }

    public JwtService(AppProperties.Jwt config) {
        this.config = config;
        this.key = Keys.hmacShaKeyFor(config.secret().getBytes(StandardCharsets.UTF_8));
    }

    public IssuedAccess createAccessToken(String userId, String role) {
        return createAccessToken(userId, role, Instant.now());
    }

    public IssuedAccess createAccessToken(String userId, String role, Instant now) {
        Duration ttl = Duration.ofMinutes(config.accessTokenMinutes());
        String token = Jwts.builder()
                .subject(userId)
                .claim("role", role)
                .claim("type", TokenType.ACCESS.wire)
                .id(randomId(8))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedAccess(token, ttl.toSeconds());
    }

    public IssuedRefresh createRefreshToken(String userId) {
        return createRefreshToken(userId, Instant.now());
    }

    public IssuedRefresh createRefreshToken(String userId, Instant now) {
        Instant expires = now.plus(Duration.ofDays(config.refreshTokenDays()));
        String jti = randomId(16);
        String token = Jwts.builder()
                .subject(userId)
                .claim("type", TokenType.REFRESH.wire)
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expires))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedRefresh(token, jti, expires);
    }

    /** Verifies signature and expiry, and that the token is of the expected type. */
    public Claims parse(String token, TokenType expected) {
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            throw new TokenException("Token has expired.");
        } catch (JwtException | IllegalArgumentException e) {
            throw new TokenException("Token is invalid.");
        }
        if (!expected.wire.equals(claims.get("type", String.class))) {
            throw new TokenException("Wrong token type: expected a " + expected.wire + " token.");
        }
        if (claims.getSubject() == null || claims.getId() == null) {
            throw new TokenException("Token is invalid.");
        }
        return claims;
    }

    private static String randomId(int bytes) {
        byte[] b = new byte[bytes];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
