package ru.agimate.common.security.jwt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("JwtService")
class JwtServiceTest {

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static JwtProperties properties(KeyPair pair, int accessExpiration, int refreshExpiration) {
        JwtProperties properties = new JwtProperties();
        properties.setPrivateKey(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        properties.setPublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        properties.setAccessExpiration(accessExpiration);
        properties.setRefreshExpiration(refreshExpiration);
        return properties;
    }

    private static JwtService service(int accessExpiration, int refreshExpiration) throws Exception {
        return new JwtService(properties(generateKeyPair(), accessExpiration, refreshExpiration));
    }

    private static AgimateUserPrincipal somePrincipal() {
        return new AgimateUserPrincipal(UUID.randomUUID().toString());
    }

    /**
     * The refresh-token revocation in {@code OAuthController} keys on {@code claims().getId()}. If the
     * jti stopped arriving there the replay check would silently pass everything — which is exactly
     * how it was broken before.
     */
    @Test
    @DisplayName("refresh token exposes its jti through getId()")
    void refreshTokenExposesJti() throws Exception {
        JwtService jwtService = service(900, 3600);
        String jwtId = UUID.randomUUID().toString();

        String token = jwtService.generateRefreshToken(somePrincipal(), jwtId);
        var wrapped = jwtService.extractClaimsFromValidRefreshToken(token, jwtId);

        assertTrue(wrapped.isPresent());
        assertEquals(jwtId, wrapped.get().claims().getId());
    }

    @Test
    @DisplayName("a refresh token does not verify against a different jti")
    void refreshTokenRejectsForeignJti() throws Exception {
        JwtService jwtService = service(900, 3600);

        String token = jwtService.generateRefreshToken(somePrincipal(), UUID.randomUUID().toString());

        assertTrue(jwtService.extractClaimsFromValidRefreshToken(token, UUID.randomUUID().toString()).isEmpty());
    }

    @Test
    @DisplayName("issued tokens carry exp")
    void issuedTokensCarryExpiration() throws Exception {
        JwtService jwtService = service(900, 3600);

        var access = jwtService.extractClaimsFromValidAccessToken(jwtService.generateAccessToken(somePrincipal()));
        String jwtId = UUID.randomUUID().toString();
        var refresh = jwtService.extractClaimsFromValidRefreshToken(
                jwtService.generateRefreshToken(somePrincipal(), jwtId), jwtId);

        assertNotNull(access.orElseThrow().claims().getExpiration());
        assertNotNull(refresh.orElseThrow().claims().getExpiration());
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expiredTokenIsRejected() throws Exception {
        // A negative lifetime puts exp outside the clock-skew tolerance without making the test wait.
        JwtService jwtService = service(-120, 3600);

        String token = jwtService.generateAccessToken(somePrincipal());

        assertTrue(jwtService.extractClaimsFromValidAccessToken(token).isEmpty());
    }

    @Test
    @DisplayName("an access token does not pass as a refresh token")
    void accessTokenIsNotARefreshToken() throws Exception {
        JwtService jwtService = service(900, 3600);

        String token = jwtService.generateAccessToken(somePrincipal());

        assertTrue(jwtService.extractClaimsFromValidRefreshToken(token, UUID.randomUUID().toString()).isEmpty());
    }

    @Test
    @DisplayName("a token signed by another key is rejected")
    void foreignSignatureIsRejected() throws Exception {
        JwtService issuer = service(900, 3600);
        JwtService verifier = service(900, 3600);

        String token = issuer.generateAccessToken(somePrincipal());

        assertTrue(verifier.extractClaimsFromValidAccessToken(token).isEmpty());
    }

    /** control-api holds only the public half; constructing the service there must not fail. */
    @Test
    @DisplayName("a verify-only service constructs without a private key")
    void verifyOnlyServiceConstructs() throws Exception {
        JwtProperties properties = properties(generateKeyPair(), 900, 3600);
        properties.setPrivateKey("");

        JwtService verifyOnly = assertDoesNotThrow(() -> new JwtService(properties));

        assertTrue(verifyOnly.extractClaimsFromValidAccessToken("not-a-token").isEmpty());
    }
}
