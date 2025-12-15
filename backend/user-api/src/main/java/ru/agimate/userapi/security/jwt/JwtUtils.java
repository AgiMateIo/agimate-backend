package ru.agimate.userapi.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import ru.agimate.userapi.security.UserPrincipal;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class JwtUtils {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration.ms:86400000}") // 24 hours default
    private Long jwtExpirationMs;

    @Value("${jwt.refresh.expiration.ms:604800000}") // 7 days default
    private Long jwtRefreshExpirationMs;

    public String generateToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        List<String> roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        claims.put("roles", roles);

        return createToken(claims, userDetails.getUsername(), jwtExpirationMs);
    }

    public String generateRefreshToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        List<String> roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        claims.put("roles", roles);
        claims.put("type", "refresh");

        // Add user ID as a custom claim to refresh token
        if (userDetails instanceof UserPrincipal) {
            claims.put("user_id", ((UserPrincipal) userDetails).getId());
        }

        // Add a unique identifier for the refresh token
        String jti = UUID.randomUUID().toString();
        claims.put("jti", jti);

        return createToken(claims, userDetails.getUsername(), jwtRefreshExpirationMs);
    }

    private String createToken(Map<String, Object> claims, String subject, Long expirationMs) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key)
                .compact();
    }

    public Boolean validateToken(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }

    public Boolean validateRefreshToken(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return "refresh".equals(claims.get("type")) && !isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public List<String> extractRoles(String token) {
        final Claims claims = extractAllClaims(token);
        // Handle both single role (String) and multiple roles (List<String>)
        Object rolesObj = claims.get("roles");
        if (rolesObj instanceof String) {
            return List.of((String) rolesObj);
        } else if (rolesObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) rolesObj;
            return roles;
        }
        return List.of(); // Return empty list if no roles
    }

    public String extractTokenType(String token) {
        final Claims claims = extractAllClaims(token);
        return claims.get("type", String.class);
    }

    public String extractJti(String token) {
        final Claims claims = extractAllClaims(token);
        return claims.get("jti", String.class);
    }

    public Long extractUserId(String token) {
        final Claims claims = extractAllClaims(token);
        Object userIdObj = claims.get("user_id");
        if (userIdObj instanceof Integer) {
            return ((Integer) userIdObj).longValue();
        } else if (userIdObj instanceof Long) {
            return (Long) userIdObj;
        } else if (userIdObj instanceof Double) {
            return ((Double) userIdObj).longValue();
        } else if (userIdObj != null) {
            return Long.parseLong(userIdObj.toString());
        }
        return null;
    }

    public String extractPubId(String token) {
        final Claims claims = extractAllClaims(token);
        return claims.get("pub_id", String.class);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }
}