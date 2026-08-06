package ru.agimate.controlapi.service.connection;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.UUIDUtils;
import ru.agimate.controlapi.abac.ConnectionAccessEvaluator;
import ru.agimate.controlapi.connectors.core.ConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.events.ConnectorCreatedEvent;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.repositories.AgentConnectionPolicyRepository;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.service.seed.ConnectorTexts;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Management of which connectors are available to which agents ({@code agent_connections}).
 *
 * <p>Internal connectors (board/memory/time/media/webchat/acp): a connection is a mode row, <b>one per
 * user</b> ({@link #ensureModeConnection}, find-or-create by {@code (connector_code, user_id)}). The
 * data's owner is resolved by the connector's code at call time from {@code ConnectorEnv} (the rule is
 * the connector's knowledge, see docs/architecture/connectors.md). Access is granted to agents by the
 * skill sync ({@code AgentSkillPolicyService}) or by the channel services (webchat/acp) through
 * {@link #bindInternal}; managing internal connectors' bindings by hand through the manage API is
 * forbidden.
 *
 * <p>External connectors (telegram/mcp/app): a connection is a concrete instance with credentials, and
 * binding is only ever to an existing id ({@link #ensureBindingToExisting}).
 *
 * <p>On the first materialisation of a mode row it emits a {@link ConnectorCreatedEvent} (registering
 * the declarative {@code @Job}s); the row then lives on independently of the bindings — there is no
 * collapse.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConnectionBindingService {

    private final ConnectionRepository connectionRepository;
    private final AgentConnectionRepository agentConnectionRepository;
    private final AgentConnectionPolicyRepository policyRepository;
    private final AgentRepository agentRepository;
    private final ConnectionAccessEvaluator accessEvaluator;
    private final ApplicationEventPublisher eventPublisher;
    private final ConnectorRegistry connectorRegistry;
    private final ConnectorTexts connectorTexts;

    /** Open an internal connector to an agent: the user's mode row plus an {@code agent_connection}. */
    @Transactional
    public AgentConnection bindInternal(UUID userId, UUID agentId, String connectorCode) {
        Connection connection = ensureModeConnection(userId, connectorCode);
        return ensureBinding(agentId, connection.getId());
    }

    /** The mode row of an internal connector (one per user): find-or-create. */
    @Transactional
    public Connection ensureModeConnection(UUID userId, String connectorCode) {
        ConnectorHandler handler = requireInternalHandler(connectorCode);
        return connectionRepository.findByUserIdAndConnectorCodeNotDeleted(userId, connectorCode).stream()
                .findFirst()
                .orElseGet(() -> createModeConnection(userId, handler));
    }

    private ConnectorHandler requireInternalHandler(String connectorCode) {
        ConnectorHandler handler = connectorRegistry.findHandler(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));
        if (!(handler instanceof InternalConnectorHandler)) {
            throw new BadRequestStatusException(
                    "Connector " + connectorCode + " is external — bind an explicit connection instance");
        }
        return handler;
    }

    /**
     * Materialisation of a mode row. A race between concurrent creations is settled by the database
     * ({@code INSERT … ON CONFLICT DO NOTHING} on {@code uq_connections_full_code_user}): the loser
     * waits for the winner's commit and re-reads its row — with no exception and without poisoning the
     * current transaction.
     */
    private Connection createModeConnection(UUID userId, ConnectorHandler handler) {
        String connectorCode = handler.connectorCode();
        UUID id = UUIDUtils.generateUUIDv8();
        int inserted = connectionRepository.insertModeConnectionIfAbsent(
                id, connectorCode, connectorCode + "_" + userId, userId,
                connectorTexts.name(connectorCode, handler.connectorName()));
        if (inserted > 0) {
            // The event comes only from whoever actually created the row (job registration is AFTER_COMMIT).
            eventPublisher.publishEvent(new ConnectorCreatedEvent(connectorCode, id.toString(), userId));
            log.info("Materialized {} mode connection {} for user {}", connectorCode, id, userId);
        }
        return connectionRepository.findByUserIdAndConnectorCodeNotDeleted(userId, connectorCode).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Mode connection missing right after materialization: " + connectorCode));
    }

    /**
     * A binding together with its connection — for the manage API (the list and the binding response).
     *
     * @param managedBySkills the binding belongs to an internal connector, so it is not removable here:
     *                        skills are its source of truth. Computed rather than left to the caller —
     *                        otherwise every client would have to keep its own list of internal codes.
     */
    public record AgentConnectionView(AgentConnection binding, Connection connection, boolean managedBySkills) {}

    /** An agent's active bindings with their connections (for the manage listing). */
    public List<AgentConnectionView> listForAgent(UUID userId, UUID agentId) {
        requireOwnedAgent(userId, agentId);
        List<AgentConnection> bindings = agentConnectionRepository.findActiveByAgentId(agentId);
        List<UUID> connectionIds = bindings.stream().map(AgentConnection::getConnectionId).toList();
        Map<UUID, Connection> byId = connectionIds.isEmpty() ? Map.of()
                : connectionRepository.findByIdInNotDeleted(connectionIds).stream()
                        .collect(Collectors.toMap(Connection::getId, c -> c));
        List<AgentConnectionView> views = new ArrayList<>();
        for (AgentConnection b : bindings) {
            Connection c = byId.get(b.getConnectionId());
            if (c != null) {
                views.add(new AgentConnectionView(b, c, isInternal(c.getConnectorCode())));
            }
        }
        return views;
    }

    /** A binding together with its agent — the reverse listing «who uses this instance». */
    public record ConnectionAgentView(AgentConnection binding, Agent agent) {}

    /**
     * Active bindings of a connection with their agents. Disabled agents stay in the output: this is an
     * inventory of the instance's use (who a deletion or a credentials change will affect), not a list
     * of trigger recipients — that has its own filter,
     * {@code AgentRepository.findBoundToConnection}.
     */
    public List<ConnectionAgentView> listForConnection(UUID userId, UUID connectionId) {
        connectionRepository.findByIdAndUserIdNotDeleted(connectionId, userId)
                .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + connectionId));
        List<AgentConnection> bindings = agentConnectionRepository.findActiveByConnectionId(connectionId);
        List<UUID> agentIds = bindings.stream().map(AgentConnection::getAgentId).toList();
        Map<UUID, Agent> byId = agentIds.isEmpty() ? Map.of()
                : agentRepository.findAllById(agentIds).stream()
                        .collect(Collectors.toMap(Agent::getId, a -> a));
        List<ConnectionAgentView> views = new ArrayList<>();
        for (AgentConnection b : bindings) {
            // The agent may have been soft-deleted (@SQLRestriction no longer returns it) while the binding lives on.
            Agent agent = byId.get(b.getAgentId());
            if (agent != null) {
                views.add(new ConnectionAgentView(b, agent));
            }
        }
        return views;
    }

    /** Bind an external instance and return the view (binding + connection) — for the manage API's response. */
    @Transactional
    public AgentConnectionView bindAndView(UUID userId, UUID agentId, UUID connectionId) {
        requireOwnedAgent(userId, agentId);
        Connection connection = requireExternalConnection(userId, connectionId);
        AgentConnection binding = ensureBinding(agentId, connection.getId());
        // requireExternalConnection has already refused an internal one.
        return new AgentConnectionView(binding, connection, false);
    }

    private void requireOwnedAgent(UUID userId, UUID agentId) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found: " + agentId));
        if (!agent.getUserId().equals(userId)) {
            throw new NotFoundStatusException("Agent not found: " + agentId);
        }
    }

    /**
     * Bind an agent to an already existing instance (by its id). For cases where the connection is
     * already chosen (a channel onto a particular telegram/mcp/app).
     */
    @Transactional
    public AgentConnection ensureBindingToExisting(UUID userId, UUID agentId, UUID connectionId) {
        connectionRepository.findByIdAndUserIdNotDeleted(connectionId, userId)
                .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + connectionId));
        return ensureBinding(agentId, connectionId);
    }

    /**
     * Unbind an external instance from an agent. Internal connectors' bindings are managed by the skill
     * sync and the channels — unbinding them by hand is forbidden (the next sync would resurrect it
     * anyway).
     */
    @Transactional
    public void unbind(UUID userId, UUID agentId, UUID connectionId) {
        requireOwnedAgent(userId, agentId);
        requireExternalConnection(userId, connectionId);
        AgentConnection binding = agentConnectionRepository.findActiveBinding(agentId, connectionId)
                .orElseThrow(() -> new NotFoundStatusException("Binding not found"));
        removeBinding(binding);
    }

    /** Drop a binding plus its policies plus the decision cache. The shared bottom of unbind and skill reconciliation. */
    @Transactional
    public void removeBinding(AgentConnection binding) {
        LocalDateTime now = LocalDateTime.now();
        agentConnectionRepository.softDelete(binding.getId(), now);
        // We drop this binding's rules — otherwise a rebind (with a new binding id) would orphan them.
        policyRepository.softDeleteByAgentConnectionId(binding.getId(), now);
        // We reset the decision cache so the revocation applies at once (rather than after the TTL).
        invalidate(binding.getAgentId(), binding.getConnectionId());
    }

    private boolean isInternal(String connectorCode) {
        return connectorRegistry.findHandler(connectorCode)
                .map(InternalConnectorHandler.class::isInstance)
                .orElse(false);
    }

    private Connection requireExternalConnection(UUID userId, UUID connectionId) {
        Connection connection = connectionRepository.findByIdAndUserIdNotDeleted(connectionId, userId)
                .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + connectionId));
        if (isInternal(connection.getConnectorCode())) {
            throw new BadRequestStatusException(
                    "Connector " + connection.getConnectorCode() + " is managed by skills");
        }
        return connection;
    }

    /**
     * Unbind a connection from every agent (when an App instance is deleted, say): a soft delete of all
     * active bindings plus their policies, and a reset of the decision cache. The connection instance
     * itself is left alone — its lifecycle belongs to its owner (AppService deletes the connection
     * separately).
     */
    @Transactional
    public void detachConnection(UUID connectionId) {
        for (AgentConnection binding : agentConnectionRepository.findActiveByConnectionId(connectionId)) {
            removeBinding(binding);
        }
    }

    /**
     * Unbind an agent from every instance (deleting an agent). Deliberately bypassing {@link #unbind}:
     * the ban on internal connectors is a rule of the manage API («do not touch by hand, the skill sync
     * will resurrect it»), not of the lifecycle, where everything is removed. Ownership is checked by
     * the caller (the agent is already loaded).
     */
    @Transactional
    public void detachAgent(UUID agentId) {
        for (AgentConnection binding : agentConnectionRepository.findActiveByAgentId(agentId)) {
            removeBinding(binding);
        }
    }

    /** Idempotent: a race between concurrent bindings is settled by the database (ON CONFLICT), then we re-read the row. */
    private AgentConnection ensureBinding(UUID agentId, UUID connectionId) {
        agentConnectionRepository.insertBindingIfAbsent(agentId, connectionId);
        AgentConnection binding = agentConnectionRepository.findActiveBinding(agentId, connectionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Binding missing right after insert: agent=" + agentId + ", connection=" + connectionId));
        // A fresh binding may have a cached deny — we reset it so access applies at once.
        invalidate(agentId, connectionId);
        return binding;
    }

    private void invalidate(UUID agentId, UUID connectionId) {
        accessEvaluator.invalidateByAgent(agentId);
        accessEvaluator.invalidateByConnection(connectionId);
    }
}
