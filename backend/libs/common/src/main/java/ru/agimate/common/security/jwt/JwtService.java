package ru.agimate.common.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class JwtService {

    private final JwtProperties jwtProperties;

    private static final String CLAIM_TYPE = "t";
    private static final String CLAIM_TYPE_REFRESH = "r";
    private static final String CLAIM_TYPE_ACCESS = "a";

    private static final String CLAIM_JWT_ID = "jti";

    private PrivateKey getPrivateKey() {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(jwtProperties.getPrivateKey());
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            return keyFactory.generatePrivate(keySpec);
        } catch (Exception e) {
            throw new JwtException("Failed to load JWT private key", e);
        }
    }

    private PublicKey getPublicKey() {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(jwtProperties.getPublicKey());
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            return keyFactory.generatePublic(keySpec);
        } catch (Exception e) {
            throw new JwtException("Failed to load JWT public key", e);
        }
    }

    public String generateAccessToken(AgimateUserPrincipal agimateUserPrincipal) {
        Map<String, Object> claims = new HashMap<>();
        List<String> roles = agimateUserPrincipal.authorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        claims.put(CLAIM_TYPE, CLAIM_TYPE_ACCESS);
        claims.put("roles", roles);

        return createToken(agimateUserPrincipal.getName(), claims);
    }

    public String generateRefreshToken(AgimateUserPrincipal agimateUserPrincipal, String jwtId) {
        Map<String, Object> claims = new HashMap<>();

        claims.put(CLAIM_TYPE, CLAIM_TYPE_REFRESH);
        claims.put(CLAIM_JWT_ID, jwtId);

        return createToken(agimateUserPrincipal.getName(), claims);
    }

    private String createToken(String subject, Map<String, Object> claims) {
        PrivateKey privateKey = getPrivateKey();
        return Jwts.builder()
                .subject(subject)
                .claims(claims)
                .issuedAt(new Date(System.currentTimeMillis()))
                .signWith(privateKey, Jwts.SIG.ES256)
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
        PublicKey publicKey = getPublicKey();

        var claims = Jwts.parser()
                .verifyWith(publicKey)
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
