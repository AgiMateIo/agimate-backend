package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.CryptoUtils;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.Connection;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * The OAuth grant of one connection: starting the flow, finishing it, keeping the tokens alive.
 *
 * <p>Only two paths obtain a grant — the user finishing authorisation, and the refresh job. Clearing
 * a rejected client and widening the requested scope edit the secret separately; they do not mint
 * tokens. The locking stays small because the job row is claimed by the scheduler through
 * {@code FOR UPDATE SKIP LOCKED}, so two nodes never refresh the same connection, and the one
 * remaining overlap — a re-authorisation racing a refresh — is settled inside
 * {@link McpOAuthStore#storeRefreshed} by comparing the client and the refresh token that were
 * exchanged with the ones now stored.
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
    private final DynamicClientRegistrar registrar;
    private final OAuthRegistrationLimiter registrationLimiter;
    private final McpOAuthProperties properties;

    /**
     * Mints the {@code state} and the PKCE verifier and returns the address to send the browser to.
     * They are born here rather than at creation time so that a user who filled the form and walked
     * away leaves no live state behind.
     *
     * <p>The client is chosen before the state: a registration is network and may fail, and a state
     * that leads to nothing is worse than no state at all. The order is the grant's client, the
     * client of an abandoned attempt, a new registration — a working reconnect neither registers nor
     * shows a second consent screen.
     */
    public String startAuthorization(Connection connection) {
        Map<String, String> credentials = store.credentials(connection);
        if (!OAuthCredentials.isOAuth(credentials)) {
            throw new ConnectorException("This connection does not use OAuth");
        }

        OAuthSetup setup = OAuthCredentials.setup(credentials);
        SelectedClient selected = selectClient(setup, credentials, connection.getUserId());
        String state = CryptoUtils.randomHex(32);
        String verifier = Pkce.generateVerifier();
        store.startFlow(connection.getId(), state, LocalDateTime.now().plus(STATE_TTL), verifier,
                OAuthCredentials.flowClientKeys(
                        selected.identity(), selected.registeredScope(), selected.secretExpiresAt()));

        return client.authorizationUrl(setup, selected.identity(), state, verifier);
    }

    private SelectedClient selectClient(OAuthSetup setup, Map<String, String> credentials, UUID userId) {
        if (setup.client().source() == OAuthClientSource.CIMD) {
            String clientId = properties.getClientId();
            if (clientId == null || clientId.isBlank()) {
                throw new ConnectorException("Client ID metadata documents are not configured on this "
                        + "installation (app.connectors.mcp.oauth.client-id)");
            }
            return new SelectedClient(
                    new ClientIdentity(OAuthClientSource.CIMD, clientId, null, TokenEndpointAuthMethod.NONE),
                    null, null);
        }

        SelectedClient grant = reusable(credentials, setup.scope(), true);
        if (grant != null) {
            return grant;
        }
        SelectedClient flow = reusable(credentials, setup.scope(), false);
        if (flow != null) {
            return flow;
        }
        registrationLimiter.check(userId, setup.client().registrationEndpoint());
        RegisteredClient registered = registrar.register(setup);
        return new SelectedClient(registered.identity(), registered.registeredScope(),
                registered.secretExpiresAt());
    }

    /**
     * A DCR client can serve this attempt when it exists, its secret has not expired and the scope it
     * was registered with covers the requested one. An empty registered scope is «unknown», not
     * «everything»: it is reusable only when nothing is requested.
     */
    private static SelectedClient reusable(Map<String, String> credentials, String requestedScope,
                                           boolean grant) {
        ClientIdentity identity = grant
                ? OAuthCredentials.grantClient(credentials)
                : OAuthCredentials.flowClient(credentials);
        if (identity == null || identity.source() != OAuthClientSource.DCR) {
            return null;
        }
        if (identity.tokenEndpointAuthMethod() != TokenEndpointAuthMethod.NONE && !identity.hasSecret()) {
            return null;
        }
        Long expiresAt = grant
                ? OAuthCredentials.grantSecretExpiresAt(credentials)
                : OAuthCredentials.flowSecretExpiresAt(credentials);
        if (expiresAt != null && expiresAt <= Instant.now().getEpochSecond()) {
            return null;
        }
        String registeredScope = grant
                ? OAuthCredentials.grantRegisteredScope(credentials)
                : OAuthCredentials.flowRegisteredScope(credentials);
        if (!OAuthCredentials.scopeCovers(registeredScope, requestedScope)) {
            return null;
        }
        return new SelectedClient(identity, registeredScope, expiresAt);
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

        ClientIdentity identity = OAuthCredentials.flowClient(credentials);
        if (identity == null) {
            // A flow started before this deployment has no flow keys; it was necessarily CIMD.
            if (setup.client().source() != OAuthClientSource.CIMD || properties.getClientId().isBlank()) {
                throw new ConnectorException("Authorization was not started for this connection");
            }
            identity = new ClientIdentity(OAuthClientSource.CIMD, properties.getClientId(), null,
                    TokenEndpointAuthMethod.NONE);
        }

        OAuthTokens tokens;
        try {
            tokens = client.exchangeCode(setup, identity, code, verifier);
        } catch (OAuthClientAuthenticationException e) {
            clearRejectedClient(connection.getId(), false);
            throw e;
        } catch (OAuthScopeRejectedException e) {
            clearRejectedClient(connection.getId(), true);
            throw e;
        }
        store.storeGrant(connection, credentials, tokens);

        if (tokens.refreshToken() == null) {
            log.info("Connection {} authorized without a refresh token; it will live until the access "
                    + "token expires", connection.getId());
        }
    }

    /**
     * The authorisation server answered with an error code instead of a code. A rejected client or a
     * rejected scope leaves the same dead client behind, so the next start must not reuse it.
     */
    public void rejectAuthorization(Connection connection, String error) {
        if ("invalid_client".equals(error)) {
            clearRejectedClient(connection.getId(), false);
        } else if ("invalid_scope".equals(error)) {
            // A server that treats the registered scope as a ceiling may also refuse the
            // unregistered offline_access; dropping it makes the retry ask for the rest.
            clearRejectedClient(connection.getId(), true);
        }
    }

    private void clearRejectedClient(UUID connectionId, boolean dropOfflineAccess) {
        try {
            store.clearRejectedClient(connectionId, dropOfflineAccess);
        } catch (RuntimeException e) {
            // The authorisation failure is the answer the user needs; a cleanup failure is worth a
            // line, not a different exception.
            log.warn("Connection {}: rejected-client cleanup failed: {}", connectionId,
                    e.getClass().getSimpleName());
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
        OAuthSetup setup = OAuthCredentials.setup(credentials);
        ClientIdentity identity = grantIdentity(credentials);
        if (identity == null) {
            // A DCR grant without its client cannot refresh: the refresh token belongs to a client
            // that no longer exists here. Without touching the network, stop the job and say why.
            store.expireWithoutClient(connectionId);
            log.info("Connection {} has no usable OAuth client; re-authorisation required", connectionId);
            return false;
        }

        String refreshToken = OAuthCredentials.refreshToken(credentials);
        if (refreshToken == null || refreshToken.isBlank()) {
            log.debug("Connection {} has no refresh token; nothing to refresh", connectionId);
            return false;
        }

        OAuthTokens tokens;
        try {
            tokens = client.refresh(setup, identity, refreshToken);
        } catch (OAuthGrantRejectedException e) {
            // The only failure that means «only the user can fix this». A timeout or a 5xx propagates
            // and the job retries — otherwise one blinking network moves a live connection into
            // «go re-authorise».
            if (store.rejectRefresh(connectionId, refreshToken)) {
                log.info("Refresh token of connection {} was rejected: re-authorisation required", connectionId);
            }
            return false;
        } catch (OAuthClientAuthenticationException e) {
            if (store.rejectRefreshClient(connectionId, refreshToken)) {
                log.info("OAuth client of connection {} was rejected: re-authorisation required", connectionId);
            }
            return false;
        } catch (OAuthScopeRejectedException e) {
            // A client bounded by a scope it cannot use is as dead as a rejected one: the next start
            // registers a new client for the current scope instead of repeating the same refusal.
            if (store.rejectRefreshClient(connectionId, refreshToken, true)) {
                log.info("OAuth scope of connection {} was rejected: re-authorisation required", connectionId);
            }
            return false;
        }

        return store.storeRefreshed(connectionId, identity.clientId(), refreshToken, tokens);
    }

    /**
     * The identity the grant was issued to. CIMD comes from configuration (with the saved client id
     * as a fallback), DCR from the grant itself; a DCR grant whose client was cleared returns null.
     */
    private ClientIdentity grantIdentity(Map<String, String> credentials) {
        if (OAuthCredentials.clientSource(credentials) == OAuthClientSource.CIMD) {
            ClientIdentity saved = OAuthCredentials.grantClient(credentials);
            String clientId = saved != null ? saved.clientId() : properties.getClientId();
            if (clientId == null || clientId.isBlank()) {
                return null;
            }
            return new ClientIdentity(OAuthClientSource.CIMD, clientId, null,
                    TokenEndpointAuthMethod.NONE);
        }
        ClientIdentity identity = OAuthCredentials.grantClient(credentials);
        if (identity == null || identity.source() != OAuthClientSource.DCR) {
            return null;
        }
        if (identity.tokenEndpointAuthMethod() != TokenEndpointAuthMethod.NONE && !identity.hasSecret()) {
            return null;
        }
        return identity;
    }

    /**
     * Whether the refresh job has anything to watch. The exchange is the only writer of the expiry,
     * so a static-token connection never has one — and neither does a grant issued without
     * {@code expires_in}, which the job could not act on anyway.
     *
     * <p>A deleted connection answers {@code false} rather than throwing: the answer feeds the startup
     * re-sync, where an exception out of this transactional store would mark the whole pass
     * rollback-only, and «declares nothing» is what removes the orphan row.
     */
    public boolean tracksExpiry(UUID connectionId) {
        return store.findConnection(connectionId)
                .map(connection -> connection.getOauthExpiresAt() != null)
                .orElse(false);
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

    private record SelectedClient(ClientIdentity identity, String registeredScope, Long secretExpiresAt) {
    }
}
