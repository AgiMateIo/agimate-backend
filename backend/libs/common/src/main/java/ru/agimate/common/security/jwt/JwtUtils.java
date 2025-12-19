package ru.agimate.common.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class JwtUtils {

    private final JwtProperties jwtProperties;

    private static final String CLAIM_TYPE = "t";
    private static final String CLAIM_JWT_ID = "jti";
    private static final String CLAIM_TYPE_REFRESH = "r";
    private static final String CLAIM_TYPE_ACCESS = "a";

    public String generateAccessToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        List<String> roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        claims.put(CLAIM_TYPE, CLAIM_TYPE_ACCESS);
        claims.put("roles", roles);

        return createToken(userDetails.getUsername(), claims);
    }

    public String generateRefreshToken(UserDetails userDetails, String jwtId) {
        Map<String, Object> claims = new HashMap<>();

        claims.put(CLAIM_TYPE, CLAIM_TYPE_REFRESH);
        claims.put(CLAIM_JWT_ID, jwtId);

        return createToken(userDetails.getUsername(), claims);
    }

    private String createToken(String subject, Map<String, Object> claims) {
        SecretKey key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes());
        return Jwts.builder()
                .subject(subject)
                .claims(claims)
                .issuedAt(new Date(System.currentTimeMillis()))
                .signWith(key)
                .compact();
    }

    public Optional<WrappedJwt> extractClaimsFromValidAccessToken(String token) {
        try {
            Claims claims = extractAllClaims(token);
            if (CLAIM_TYPE_ACCESS.equals(claims.get(CLAIM_TYPE))) {
                return Optional.of(new WrappedJwt(token, claims));
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<WrappedJwt> extractClaimsFromValidRefreshToken(String token, String jwtId) {
        try {
            Claims claims = extractAllClaims(token);
            if (CLAIM_TYPE_REFRESH.equals(claims.get(CLAIM_TYPE)) && jwtId.equals(claims.get(CLAIM_JWT_ID))) {
                return Optional.of(new WrappedJwt(token, claims));
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Claims extractAllClaims(String token) {
        SecretKey key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes());

        var claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Integer expiration = switch ((String) claims.getOrDefault(CLAIM_TYPE, "undefined")) {
            case CLAIM_TYPE_ACCESS -> jwtProperties.getAccessExpiration();
            case CLAIM_TYPE_REFRESH -> jwtProperties.getRefreshExpiration();
            default -> throw new JwtException("Undefined jwt type: " + claims.getOrDefault(CLAIM_TYPE, "undefined"));
        };

        if (isClaimsExpired(claims, expiration)) {
            throw new JwtException("JWT token is expired");
        }

        return claims;

    }

    private Boolean isClaimsExpired(Claims claims, Integer seconds) {
        if (claims.getExpiration() != null) {
            return claims.getExpiration().before(new Date());
        }

        if (claims.getIssuedAt() == null) {
            return true;
        }

        return claims.getIssuedAt().toInstant().plusSeconds(seconds).isBefore(Instant.now());
    }
}
