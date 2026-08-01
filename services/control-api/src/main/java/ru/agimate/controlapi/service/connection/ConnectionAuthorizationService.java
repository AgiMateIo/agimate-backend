package ru.agimate.controlapi.service.connection;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.events.ConnectorCreatedEvent;
import ru.agimate.controlapi.connectors.integrations.mcp.McpConnectorService;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.McpOAuthService;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The generic half of the OAuth flow: who owns the connection, whether the {@code state} is still
 * alive, and when the instance becomes usable. The protocol half — discovery, the code exchange, the
 * issuer check — lives in the connector.
 *
 * <p>There is no public endpoint anywhere in this flow. The browser comes back to a page of the
 * front, and the finishing call is an ordinary authenticated request — which is also what binds the
 * grant to the user who started it: an authorisation code brought by somebody else's browser cannot
 * be redeemed, because redeeming it requires being logged in as the connection's owner.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConnectionAuthorizationService {

    private final ConnectionRepository connectionRepository;
    private final ConnectionService connectionService;
    private final McpOAuthService oauthService;
    private final ApplicationEventPublisher eventPublisher;

    /** Where to send the browser. Also the way back for a re-authorisation and for a wider scope. */
    @Transactional
    public String startAuthorization(UUID connectionId, UUID userId) {
        Connection connection = connectionService.getOwnedConnection(connectionId, userId);
        requireOAuthCapable(connection);
        try {
            return oauthService.startAuthorization(connection);
        } catch (ConnectorException e) {
            throw new BadRequestStatusException(e.getMessage());
        }
    }

    /**
     * Finishes what the browser brought back.
     *
     * @param error the authorisation server's error code instead of a code — a declined consent is a
     *              normal outcome, not a failure: the connection simply stays unauthorised
     * @param issuer {@code iss} from the response (RFC 9207); verified even on the error path, and on
     *               a mismatch nothing from the server is shown at all
     */
    @Transactional
    public Connection complete(UUID userId, String state, String code, String error, String issuer) {
        Connection connection = connectionRepository.findByOauthStateAndUserId(state, userId)
                .orElseThrow(() -> new NotFoundStatusException("Authorization request not found"));

        if (connection.getOauthStateExpiresAt() == null
                || connection.getOauthStateExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BadRequestStatusException("Authorization request has expired; start it again");
        }
        // Conditional on the state still being there: a double callback would otherwise run two
        // parallel attempts to redeem one authorisation code.
        if (connectionRepository.burnOauthState(connection.getId(), state) == 0) {
            throw new BadRequestStatusException("Authorization request has already been used");
        }
        // The bulk update went around the persistence context, which still holds the old values — and
        // the entity is saved again below. Without this the flush would write the state back.
        connection.setOauthState(null);
        connection.setOauthStateExpiresAt(null);

        try {
            if (error != null && !error.isBlank()) {
                oauthService.verifyIssuer(connection, issuer);
                log.info("Authorization of connection {} was not granted: {}", connection.getId(), error);
                throw new BadRequestStatusException("Authorization was not granted (" + error + ")");
            }
            if (code == null || code.isBlank()) {
                throw new BadRequestStatusException("Authorization response carries neither a code nor an error");
            }
            oauthService.completeAuthorization(connection, code, issuer);
        } catch (ConnectorException e) {
            throw new BadRequestStatusException(e.getMessage());
        }

        // Published here rather than at creation: tool discovery hangs on this event, and before the
        // exchange it would have been guaranteed a 401.
        eventPublisher.publishEvent(new ConnectorCreatedEvent(
                connection.getConnectorCode(), connection.getId().toString(), connection.getUserId()));
        log.info("Connection {} authorized", connection.getId());
        return connection;
    }

    private static void requireOAuthCapable(Connection connection) {
        if (!McpConnectorService.CONNECTOR_CODE.equals(connection.getConnectorCode())) {
            throw new BadRequestStatusException(
                    "Connector " + connection.getConnectorCode() + " does not support authorization");
        }
    }
}
