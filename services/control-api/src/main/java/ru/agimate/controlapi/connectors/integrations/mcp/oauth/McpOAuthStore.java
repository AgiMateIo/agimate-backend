package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.connectors.core.ConnectorException;
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
import java.util.Set;
import java.util.UUID;

/**
 * Every write the OAuth flow makes: the connection's row and its secret. A separate bean from
 * {@link McpOAuthService} and not a private method of it — a self-invocation would sail straight past
 * the proxy and run without the transaction the annotation promises.
 *
 * <p>{@code SecretService} re-encrypts the whole set of fields at once, so «append just the tokens»
 * does not exist: every write here is read-modify-write.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class McpOAuthStore {

    private final ConnectionRepository connectionRepository;
    private final SecretRepository secretRepository;
    private final SecretService secretService;

    public Connection connection(UUID connectionId) {
        return connectionRepository.findByIdNotDeleted(connectionId)
                .orElseThrow(() -> new ConnectorException("Connection not found: " + connectionId));
    }

    public Map<String, String> credentials(Connection connection) {
        if (connection.getSecretId() == null) {
            throw new ConnectorException("Connection has no credentials: " + connection.getId());
        }
        return secretService.reveal(secret(connection), connection.getId());
    }

    /** Parks the PKCE verifier in the secret and the one-time {@code state} on the row. */
    @Transactional
    public void startFlow(Connection connection, String state, LocalDateTime stateExpiresAt, String verifier) {
        Map<String, String> updated = new LinkedHashMap<>(credentials(connection));
        updated.put(OAuthCredentials.CODE_VERIFIER, verifier);
        secretService.update(secret(connection), connection.getId(), updated);

        connection.setOauthState(state);
        connection.setOauthStateExpiresAt(stateExpiresAt);
        connectionRepository.save(connection);
    }

    /** A grant obtained by the user: unconditional, the authorisation just happened. */
    @Transactional
    public void storeGrant(Connection connection, Map<String, String> credentials, OAuthTokens tokens) {
        secretService.update(secret(connection), connection.getId(),
                OAuthCredentials.withTokens(credentials, tokens));
        connection.setAuthStatus(ConnectionAuthStatus.AUTHORIZED);
        connection.setOauthExpiresAt(tokens.expiresAt());
        connectionRepository.save(connection);
    }

    /**
     * A refreshed grant — but only if the refresh token we exchanged is still the stored one. A
     * re-authorisation that finished while we were talking to the authorisation server has already
     * written a newer grant, and overwriting it would revoke a chain the user just created.
     *
     * @return whether the fresh tokens were actually stored
     */
    @Transactional
    public boolean storeRefreshed(UUID connectionId, String exchangedRefreshToken, OAuthTokens tokens) {
        Connection connection = connection(connectionId);
        Map<String, String> current = credentials(connection);
        if (!exchangedRefreshToken.equals(OAuthCredentials.refreshToken(current))) {
            log.info("Connection {} was re-authorised while refreshing; discarding the stale grant",
                    connectionId);
            return false;
        }
        storeGrant(connection, current, tokens);
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
        Connection connection = connection(connectionId);
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
        secretService.update(secret(connection), connection.getId(), updated);
    }

    private static Set<String> scopeSet(String scope) {
        if (scope == null || scope.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(List.of(scope.trim().split("\\s+")));
    }

    /** The grant is dead and only the user can fix it. Idempotent: the job may arrive here twice. */
    @Transactional
    public void markExpired(UUID connectionId) {
        connectionRepository.findByIdNotDeleted(connectionId).ifPresent(connection -> {
            if (connection.getAuthStatus() != ConnectionAuthStatus.AUTH_EXPIRED) {
                connection.setAuthStatus(ConnectionAuthStatus.AUTH_EXPIRED);
                connectionRepository.save(connection);
            }
        });
    }

    private Secret secret(Connection connection) {
        return secretRepository.findById(connection.getSecretId())
                .orElseThrow(() -> new ConnectorException(
                        "Secret not found for connection " + connection.getId()));
    }
}
