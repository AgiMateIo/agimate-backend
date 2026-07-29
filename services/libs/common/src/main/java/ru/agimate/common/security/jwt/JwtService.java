package ru.agimate.common.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.security.core.GrantedAuthority;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class JwtService {

    private final JwtProperties jwtProperties;

    /** Null where the half is not configured: control-api verifies only and has no signing key. */
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    private static final String CLAIM_TYPE = "t";
    private static final String CLAIM_TYPE_REFRESH = "r";
    private static final String CLAIM_TYPE_ACCESS = "a";

    private static final String CLAIM_JWT_ID = "jti";

    /** Tolerance for clock drift between the issuing and the verifying instance. */
    private static final long CLOCK_SKEW_SECONDS = 30;

    /**
     * Keys are parsed once: {@link KeyFactory} is expensive and this runs on every request. A key
     * that is present but malformed fails here, at startup, rather than on the first token.
     */
    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.privateKey = loadPrivateKey(jwtProperties.getPrivateKey());
        this.publicKey = loadPublicKey(jwtProperties.getPublicKey());
    }

    private static PrivateKey loadPrivateKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new JwtException("Failed to load JWT private key", e);
        }
    }

    private static PublicKey loadPublicKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new JwtException("Failed to load JWT public key", e);
        }
    }

    private PrivateKey requirePrivateKey() {
        if (privateKey == null) {
            throw new JwtException("JWT private key is not configured — this service cannot issue tokens");
        }
        return privateKey;
    }

    private PublicKey requirePublicKey() {
        if (publicKey == null) {
            throw new JwtException("JWT public key is not configured — this service cannot verify tokens");
        }
        return publicKey;
    }

    public String generateAccessToken(AgimateUserPrincipal agimateUserPrincipal) {
        Map<String, Object> claims = new HashMap<>();
        List<String> roles = agimateUserPrincipal.authorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        claims.put(CLAIM_TYPE, CLAIM_TYPE_ACCESS);
        claims.put("roles", roles);

        return createToken(agimateUserPrincipal.getName(), claims, jwtProperties.getAccessExpiration());
    }

    public String generateRefreshToken(AgimateUserPrincipal agimateUserPrincipal, String jwtId) {
        Map<String, Object> claims = new HashMap<>();

        claims.put(CLAIM_TYPE, CLAIM_TYPE_REFRESH);
        claims.put(CLAIM_JWT_ID, jwtId);

        return createToken(agimateUserPrincipal.getName(), claims, jwtProperties.getRefreshExpiration());
    }

    /**
     * The lifetime is baked into {@code exp} rather than recomputed at verification time: otherwise
     * changing the configured expiration retroactively re-dates every token already in the wild, and
     * nothing outside this class (a gateway, a debugger) can tell whether a token is still valid.
     */
    private String createToken(String subject, Map<String, Object> claims, int expirationSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(requirePrivateKey(), Jwts.SIG.ES256)
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
        // exp is enforced by the parser itself, which is why nothing below re-checks it.
        var claims = Jwts.parser()
                .verifyWith(requirePublicKey())
                .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        if (claims.getExpiration() == null) {
            requireNotExpiredByIssuedAt(claims);
        }

        return claims;
    }

    /**
     * Fallback for tokens issued before {@code exp} was set, which stay in circulation for one
     * refresh lifetime after the deploy. Delete once that window has passed.
     */
    private void requireNotExpiredByIssuedAt(Claims claims) {
        Integer lifetime = switch ((String) claims.getOrDefault(CLAIM_TYPE, "undefined")) {
            case CLAIM_TYPE_ACCESS -> jwtProperties.getAccessExpiration();
            case CLAIM_TYPE_REFRESH -> jwtProperties.getRefreshExpiration();
            default -> throw new JwtException("Undefined jwt type: " + claims.getOrDefault(CLAIM_TYPE, "undefined"));
        };

        if (claims.getIssuedAt() == null
                || claims.getIssuedAt().toInstant().plusSeconds(lifetime).isBefore(Instant.now())) {
            throw new JwtException("JWT token is expired");
        }
    }
}
