package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.CryptoUtils;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.Connection;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * The OAuth grant of one connection: starting the flow, finishing it, keeping the tokens alive.
 *
 * <p>Only two things ever write a grant — the user finishing authorisation, and the refresh job.
 * That is what makes locking unnecessary: the job row is claimed by the scheduler through
 * {@code FOR UPDATE SKIP LOCKED}, so two nodes never refresh the same connection, and the one
 * remaining overlap — a re-authorisation racing a refresh — is settled inside
 * {@link McpOAuthStore#storeRefreshed} by comparing the refresh token that was exchanged with the one
 * now stored.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpOAuthService {

    /** Long enough to log in on a second device, short enough that an abandoned flow does not linger. */
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    /** Refresh a token that dies within this window; the job's interval is shorter than it. */
    private static final Duration REFRESH_HORIZON = Duration.ofMinutes(10);

    private final McpOAuthStore store;
    private final McpOAuthClient client;

    /**
     * Mints the {@code state} and the PKCE verifier and returns the address to send the browser to.
     * They are born here rather than at creation time so that a user who filled the form and walked
     * away leaves no live state behind.
     */
    public String startAuthorization(Connection connection) {
        Map<String, String> credentials = store.credentials(connection);
        if (!OAuthCredentials.isOAuth(credentials)) {
            throw new ConnectorException("This connection does not use OAuth");
        }

        String state = CryptoUtils.randomHex(32);
        String verifier = Pkce.generateVerifier();
        store.startFlow(connection, state, LocalDateTime.now().plus(STATE_TTL), verifier);

        return client.authorizationUrl(OAuthCredentials.setup(credentials), state, verifier);
    }

    /**
     * Redeems the authorisation code. The caller has already burnt the {@code state} and proved
     * ownership; what is left is protocol — the issuer check and the exchange.
     *
     * <p>Tokens are written only on success: a user may start re-authorisation while holding a
     * working grant (a wider scope, another account) and never finish it.
     */
    public void completeAuthorization(Connection connection, String code, String issuer) {
        Map<String, String> credentials = store.credentials(connection);
        OAuthSetup setup = OAuthCredentials.setup(credentials);
        requireIssuer(setup, issuer);

        String verifier = credentials.get(OAuthCredentials.CODE_VERIFIER);
        if (verifier == null || verifier.isBlank()) {
            throw new ConnectorException("Authorization was not started for this connection");
        }

        OAuthTokens tokens = client.exchangeCode(setup, code, verifier);
        store.storeGrant(connection, credentials, tokens);

        if (tokens.refreshToken() == null) {
            log.info("Connection {} authorized without a refresh token; it will live until the access "
                    + "token expires", connection.getId());
        }
    }

    /**
     * Verifies {@code iss} of an authorisation response that carries an error. Done before the caller
     * shows anything at all: on a mismatch even {@code error_description} must not reach the user.
     */
    public void verifyIssuer(Connection connection, String issuer) {
        requireIssuer(OAuthCredentials.setup(store.credentials(connection)), issuer);
    }

    /**
     * The refresh job's single iteration. A no-op for a static-token connection and for one that was
     * never given a refresh token — and that is decided from the {@code oauth_expires_at} column,
     * without touching the network.
     *
     * @return whether a new access token was actually obtained
     */
    public boolean refreshIfNeeded(UUID connectionId) {
        Connection connection = store.connection(connectionId);
        LocalDateTime expiresAt = connection.getOauthExpiresAt();
        if (expiresAt == null || expiresAt.isAfter(LocalDateTime.now().plus(REFRESH_HORIZON))) {
            return false;
        }

        Map<String, String> credentials = store.credentials(connection);
        String refreshToken = OAuthCredentials.refreshToken(credentials);
        if (refreshToken == null || refreshToken.isBlank()) {
            log.debug("Connection {} has no refresh token; nothing to refresh", connectionId);
            return false;
        }

        OAuthTokens tokens;
        try {
            tokens = client.refresh(OAuthCredentials.setup(credentials), refreshToken);
        } catch (OAuthGrantRejectedException e) {
            // The only failure that means «only the user can fix this». A timeout or a 5xx propagates
            // and the job retries — otherwise one blinking network moves a live connection into
            // «go re-authorise».
            store.markExpired(connectionId);
            log.info("Refresh token of connection {} was rejected: re-authorisation required", connectionId);
            return false;
        }

        return store.storeRefreshed(connectionId, refreshToken, tokens);
    }

    /** A 401 in the middle of a tool call: mark it and let the job try to repair on its next tick. */
    public void markExpired(UUID connectionId) {
        store.markExpired(connectionId);
    }

    /**
     * A 403 {@code insufficient_scope}: the grant is alive, it is simply too narrow. The scopes the
     * server asked for are remembered so that the next authorisation asks for them together with
     * everything already granted — otherwise re-authorising would trade one permission for another.
     */
    public void widenScope(UUID connectionId, String requiredScope) {
        store.widenScope(connectionId, requiredScope);
    }

    /**
     * RFC 9207. An absent {@code iss} is fine when the server never advertised support for it; a
     * present one is compared by simple string comparison — no case folding, no default-port elision,
     * no trailing-slash normalisation.
     */
    private static void requireIssuer(OAuthSetup setup, String issuer) {
        if (issuer == null || issuer.isBlank()) {
            return;
        }
        if (!issuer.equals(setup.issuer())) {
            throw new ConnectorException("The authorization response came from a different issuer");
        }
    }
}
