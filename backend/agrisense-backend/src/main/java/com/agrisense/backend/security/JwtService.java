package com.agrisense.backend.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/*
 * Issues and verifies the signed (HS256) tokens returned at login.
 * The secret comes from agrisense.jwt.secret (env AGRISENSE_JWT_SECRET).
 */
@Service
public class JwtService {

    public static final String ROLE_CLAIM = "role";
    public static final String EMAIL_CLAIM = "email";

    private final SecretKey key;
    private final Duration expiration;

    public JwtService(
            @Value("${agrisense.jwt.secret}") String secret,
            @Value("${agrisense.jwt.expiration-minutes:480}") long expirationMinutes) {

        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);

        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "agrisense.jwt.secret must be at least 32 bytes");
        }

        this.key = Keys.hmacShaKeyFor(bytes);
        this.expiration = Duration.ofMinutes(expirationMinutes);
    }

    public String generateToken(Long farmerId, String email, String role) {

        Date now = new Date();

        return Jwts.builder()
                .subject(String.valueOf(farmerId))
                .claim(EMAIL_CLAIM, email)
                .claim(ROLE_CLAIM, role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration.toMillis()))
                .signWith(key)
                .compact();
    }

    /*
     * Returns the verified claims, or null when the token is
     * missing, malformed, wrongly signed or expired.
     */
    public Claims parse(String token) {

        if (token == null || token.isBlank()) {
            return null;
        }

        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
