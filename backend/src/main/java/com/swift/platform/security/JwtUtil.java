package com.swift.platform.security;

import com.swift.platform.config.AppConfig;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;

@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final AppConfig appConfig;

    private Key signingKey() {
        return Keys.hmacShaKeyFor(appConfig.getJwtSecret().getBytes());
    }

    public String generateToken(String employeeId, String role, String name, String email) {
        return Jwts.builder()
                .setSubject(employeeId)
                .claim("role",  role)
                .claim("name",  name)
                .claim("email", email)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + appConfig.getJwtExpiration()))
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public Claims extractClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey()).build()
                .parseClaimsJws(token).getBody();
    }

    public String extractEmployeeId(String token) { return extractClaims(token).getSubject(); }
    public String extractRole(String token)        { return extractClaims(token).get("role",  String.class); }
    public String extractName(String token)        { return extractClaims(token).get("name",  String.class); }
    public String extractEmail(String token)       { return extractClaims(token).get("email", String.class); }

    public boolean isTokenValid(String token) {
        try { extractClaims(token); return true; }
        catch (JwtException | IllegalArgumentException e) { return false; }
    }
}
