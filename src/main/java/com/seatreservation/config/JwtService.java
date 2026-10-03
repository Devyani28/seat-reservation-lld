package com.seatreservation.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms}") long expirationMs) {
        // Pad secret to at least 32 bytes for HS256
        String padded = secret.length() < 32
            ? secret + "0".repeat(32 - secret.length())
            : secret;
        this.key = Keys.hmacShaKeyFor(padded.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(String userId, String role) {
        return Jwts.builder()
                   .subject(userId)
                   .claim("role", role)
                   .issuedAt(new Date())
                   .expiration(new Date(System.currentTimeMillis() + expirationMs))
                   .signWith(key)
                   .compact();
    }

    public Claims parseToken(String token) throws JwtException {
        return Jwts.parser()
                   .verifyWith(key)
                   .build()
                   .parseSignedClaims(token)
                   .getPayload();
    }

    public String extractUserId(String token) {
        return parseToken(token).getSubject();
    }

    public String extractRole(String token) {
        return parseToken(token).get("role", String.class);
    }
}
