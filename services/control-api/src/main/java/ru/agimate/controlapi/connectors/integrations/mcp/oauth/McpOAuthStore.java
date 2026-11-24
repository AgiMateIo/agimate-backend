package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.connectors.core.ConnectionCredentialStore;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.events.ConnectorModifiedEvent;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.enums.ConnectionAuthStatus;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.SecretRepository;
import ru.agimate.controlapi.service.secret.SecretService;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Every write the OAuth flow makes: the connection's row and its secret. A separate bean from
 * {@link McpOAuthService} and not a private method of it — a self-invocation would sail straight past
 * the proxy and run without the transaction the annotation promises.
 *
 * <p>{@link ConnectionCredentialStore} replaces the whole set of fields at once, so «append just the
 * tokens» does not exist: every write here is read-modify-write, and every one that can race another
 * writer re-reads the row under {@code FOR UPDATE} first.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class McpOAuthStore {

    private final ConnectionRepository connectionRepository;
    private final SecretRepository secretRepository;
    private final SecretService secretService;
    private final ConnectionCredentialStore credentialStore;
    private final ApplicationEventPublisher eventPublisher;

    public Connection connection(UUID connectionId) {
        return findConnection(connectionId)
                .orElseThrow(() -> new ConnectorException("Connection not found: " + connectionId));
    }

    public Optional<Connection> findConnection(UUID connectionId) {
        return connectionRepository.findByIdNotDeleted(connectionId);
    }

    public Map<String, String> credentials(Connection connection) {
        if (connection.getSecretId() == null) {
            throw new ConnectorException("Connection has no credentials: " + connection.getId());
        }
        return credentialStore.read(connection);
    }

    /**
     * Parks the PKCE verifier and the client of this attempt in the secret and the one-time
     * {@code state} on the row. The row is re-read under {@code FOR UPDATE}: the connection was
     * loaded before the network call that produced the client, and saving that snapshot would write
     * back a stale {@code deleted_at}, {@code enabled}, {@code auth_status} or {@code oauth_expires_at}.
     */
    @Transactional
    public void startFlow(UUID connectionId, String state, LocalDateTime stateExpiresAt, String verifier,
                          Map<String, String> flowClientKeys) {
        Connection connection = lockedConnection(connectionId);
        Map<String, String> updated = new LinkedHashMap<>(credentials(connection));
        OAuthCredentials.clearFlowClient(updated);
        updated.put(OAuthCredentials.CODE_VERIFIER, verifier);
        updated.putAll(flowClientKeys);
        credentialStore.write(connection, updated);

        connection.setOauthState(state);
        connection.setOauthStateExpiresAt(stateExpiresAt);
        connectionRepository.save(connection);
    }

    /** A grant obtained by the user: unconditional, the authorisation just happened. */
    @Transactional
    public void storeGrant(Connection connection, Map<String, String> credentials, OAuthTokens tokens) {
        credentialStore.write(connection, OAuthCredentials.withGrant(credentials, tokens));
        connection.setAuthStatus(ConnectionAuthStatus.AUTHORIZED);
        connection.setOauthExpiresAt(tokens.expiresAt());
        connectionRepository.save(connection);
    }

    /**
     * A refreshed grant — but only if the refresh token we exchanged is still the stored one, and the
     * client is still the one it was issued to. A re-authorisation that finished while we were talking
     * to the authorisation server has already written a newer grant, and overwriting it would revoke a
     * chain the user just created.
     *
     * @return whether the fresh tokens were actually stored
     */
    @Transactional
    public boolean storeRefreshed(UUID connectionId, String exchangedClientId,
                                  String exchangedRefreshToken, OAuthTokens tokens) {
        Connection connection = lockedConnection(connectionId);
        Map<String, String> current = credentials(connection);
        ClientIdentity currentClient = OAuthCredentials.grantClient(current);
        if (!exchangedRefreshToken.equals(OAuthCredentials.refreshToken(current))
                || (currentClient != null && !currentClient.clientId().equals(exchangedClientId))) {
            log.info("Connection {} was re-authorised while refreshing; discarding the stale grant",
                    connectionId);
            return false;
        }
        credentialStore.write(connection, OAuthCredentials.withRefreshedTokens(current, tokens));
        connection.setAuthStatus(ConnectionAuthStatus.AUTHORIZED);
        connection.setOauthExpiresAt(tokens.expiresAt());
        connectionRepository.save(connection);
        return true;
    }

    /**
     * Records that the next authorisation must ask for more. The union with what was already granted
     * is the point: a server challenges with the scopes of the current operation alone, and asking for
     * only those would drop the permissions everything else relies on.
     *
     * @param requiredScope space-delimited scopes from the {@code insufficient_scope} challenge
     */
    @Transactional
    public void widenScope(UUID connectionId, String requiredScope) {
        Connection connection = lockedConnection(connectionId);
        Map<String, String> credentials = credentials(connection);
        Set<String> scopes = new LinkedHashSet<>();
        scopes.addAll(scopeSet(credentials.get(OAuthCredentials.SCOPE_GRANTED)));
        scopes.addAll(scopeSet(credentials.get(OAuthCredentials.SCOPE_REQUESTED)));
        scopes.addAll(scopeSet(requiredScope));
        if (scopes.isEmpty()) {
            return;
        }

        Map<String, String> updated = new LinkedHashMap<>(credentials);
        updated.put(OAuthCredentials.SCOPE_REQUESTED, String.join(" ", scopes));
        credentialStore.write(connection, updated);
    }

    private static Set<String> scopeSet(String scope) {
        if (scope == null || scope.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(List.of(scope.trim().split("\\s+")));
    }

    /**
     * The authorisation server refused the refresh token. It is erased together with the expiry so
     * that the job's next tick ends on «nothing to refresh» instead of sending a dead token to the
     * server every five minutes until the user re-authorises. The client stays: it may still be alive,
     * and the next start can reuse it.
     *
     * <p>Guarded like {@link #storeRefreshed}: a re-authorisation that finished during the exchange
     * has written a new chain, and that one is neither erased nor expired.
     *
     * @return whether the grant was actually marked dead
     */
    @Transactional
    public boolean rejectRefresh(UUID connectionId, String rejectedRefreshToken) {
        Connection connection = lockedConnection(connectionId);
        Map<String, String> current = credentials(connection);
        if (!rejectedRefreshToken.equals(OAuthCredentials.refreshToken(current))) {
            log.info("Connection {} was re-authorised while refreshing; keeping the new grant", connectionId);
            return false;
        }
        Map<String, String> updated = new LinkedHashMap<>(current);
        updated.remove(OAuthCredentials.REFRESH_TOKEN);
        credentialStore.write(connection, updated);
        expire(connection);
        return true;
    }

    /**
     * The authorisation server refused the client itself. Its credentials are erased and
     * {@code oauth_client_source} is kept: a DCR grant without a client means «register a new one»,
     * while a missing source would read as CIMD.
     *
     * @return whether the grant was actually marked dead
     */
    @Transactional
    public boolean rejectRefreshClient(UUID connectionId, String rejectedRefreshToken) {
        return rejectRefreshClient(connectionId, rejectedRefreshToken, false);
    }

    /**
     * The same as {@link #rejectRefreshClient(UUID, String)}, and optionally drops
     * {@code offline_access} when the server refused the scope rather than the client.
     */
    @Transactional
    public boolean rejectRefreshClient(UUID connectionId, String rejectedRefreshToken,
                                       boolean dropOfflineAccess) {
        Connection connection = lockedConnection(connectionId);
        Map<String, String> current = credentials(connection);
        if (!rejectedRefreshToken.equals(OAuthCredentials.refreshToken(current))) {
            log.info("Connection {} was re-authorised while refreshing; keeping the new grant", connectionId);
            return false;
        }
        Map<String, String> updated = new LinkedHashMap<>(current);
        updated.remove(OAuthCredentials.REFRESH_TOKEN);
        updated.remove(OAuthCredentials.SCOPE_GRANTED);
        ClientIdentity flow = OAuthCredentials.flowClient(current);
        String rejectedClientId = current.get(OAuthCredentials.CLIENT_ID);
        OAuthCredentials.clearGrantClient(updated);
        if (flow != null && flow.source() == OAuthClientSource.DCR
                && flow.clientId().equals(rejectedClientId)) {
            // The pending attempt used the same dead client; leaving it in flow keys would make the
            // next start reuse it instead of registering a new one.
            OAuthCredentials.clearFlowClient(updated);
        }
        if (dropOfflineAccess) {
            OAuthCredentials.dropOfflineAccess(updated);
        }
        credentialStore.write(connection, updated);
        expire(connection);
        return true;
    }

    /**
     * A DCR grant whose client is gone and whose refresh cannot even start. The job row has to
     * disappear, so the expiry is cleared and {@code ConnectorModifiedEvent} makes the listener
     * re-sync {@code connector_jobs}; the status says «the user must act» without pretending a
     * network attempt was made.
     */
    @Transactional
    public void expireWithoutClient(UUID connectionId) {
        connectionRepository.findByIdNotDeletedForUpdate(connectionId).ifPresent(connection -> {
            // A re-authorisation may have finished after the caller read the secret; its client is
            // usable, and expiring that grant would undo a grant the user just created.
            if (!hasUsableClient(connection)) {
                expire(connection);
            }
        });
    }

    private boolean hasUsableClient(Connection connection) {
        if (connection.getSecretId() == null) {
            return false;
        }
        ClientIdentity client;
        try {
            client = OAuthCredentials.grantClient(credentials(connection));
        } catch (RuntimeException e) {
            // A missing, unreadable or undecryptable secret is not a client; expiring is the honest
            // outcome, and a missing KEK must not leave the refresh job ticking with an error.
            log.warn("Connection {}: credentials unreadable while checking the OAuth client: {}",
                    connection.getId(), e.getClass().getSimpleName());
            return false;
        }
        return client != null
                && (client.tokenEndpointAuthMethod() == TokenEndpointAuthMethod.NONE || client.hasSecret());
    }

    private void expire(Connection connection) {
        connection.setOauthExpiresAt(null);
        connection.setAuthStatus(ConnectionAuthStatus.AUTH_EXPIRED);
        connectionRepository.save(connection);
        eventPublisher.publishEvent(new ConnectorModifiedEvent(
                connection.getConnectorCode(), connection.getId().toString(), connection.getUserId()));
    }

    /**
     * The client of a completed attempt was rejected. Runs outside the rolled-back {@code complete}
     * transaction and touches only the secret: {@link ConnectionCredentialStore#write} would also
     * write {@code connection.params} and would wait on the row lock the outer transaction still
     * holds. This is the cleanup for a grant that has no expiry or refresh token, where refresh never
     * runs to discover the dead client.
     *
     * <p>If the flow used the grant's own client, the grant loses its client credentials, its refresh
     * token and its granted scope; {@code oauth_client_source} stays. A flow client that differed was
     * only this attempt's and the grant is untouched. CIMD is always only this attempt's: its identity
     * comes from configuration.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearRejectedClient(UUID connectionId) {
        clearRejectedClient(connectionId, false);
    }

    /**
     * @param dropOfflineAccess whether the refusal was about the scope rather than the client; then
     *                          {@code offline_access} is removed so the next start asks for the rest
     *                          instead of registering an identical client for the same refusal
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearRejectedClient(UUID connectionId, boolean dropOfflineAccess) {
        Optional<Connection> found = connectionRepository.findByIdNotDeleted(connectionId);
        if (found.isEmpty() || found.get().getSecretId() == null) {
            return;
        }
        Optional<Secret> stored = secretRepository.findById(found.get().getSecretId());
        if (stored.isEmpty()) {
            return;
        }
        Secret secret = stored.get();
        Map<String, String> current = secretService.reveal(secret, connectionId);
        Map<String, String> updated = new LinkedHashMap<>(current);

        ClientIdentity flow = OAuthCredentials.flowClient(current);
        String grantClientId = current.get(OAuthCredentials.CLIENT_ID);
        if (flow != null && flow.source() == OAuthClientSource.DCR
                && flow.clientId().equals(grantClientId)) {
            OAuthCredentials.clearGrantClient(updated);
            updated.remove(OAuthCredentials.REFRESH_TOKEN);
            updated.remove(OAuthCredentials.SCOPE_GRANTED);
        }
        if (dropOfflineAccess) {
            OAuthCredentials.dropOfflineAccess(updated);
        }
        OAuthCredentials.clearFlowClient(updated);
        secretService.update(secret, connectionId, updated);
        log.info("Connection {}: rejected client cleared", connectionId);
    }

    /**
     * The grant is dead and only the user can fix it. Idempotent: the job may arrive here twice. The
     * refresh token stays — after a 401 in a tool call it may still be alive, see {@link #rejectRefresh}.
     */
    @Transactional
    public void markExpired(UUID connectionId) {
        connectionRepository.findByIdNotDeletedForUpdate(connectionId).ifPresent(connection -> {
            if (connection.getAuthStatus() != ConnectionAuthStatus.AUTH_EXPIRED) {
                connection.setAuthStatus(ConnectionAuthStatus.AUTH_EXPIRED);
                connectionRepository.save(connection);
            }
        });
    }

    private Connection lockedConnection(UUID connectionId) {
        return connectionRepository.findByIdNotDeletedForUpdate(connectionId)
                .orElseThrow(() -> new ConnectorException("Connection not found: " + connectionId));
    }
}
