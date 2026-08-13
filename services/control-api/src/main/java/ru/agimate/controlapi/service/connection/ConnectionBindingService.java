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
 * the connector's knowledge, see docs/architecture/connectors.md). Access is granted the same way as
 * for an external instance — by the user, through the manage API — except that the connector is named
 * by code, because the mode row may not exist yet. The channel services (webchat/acp) also open one
 * through {@link #bindInternal} when they create a channel.
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
     * ({@code INSERT … ON CONFLICT DO NOTHING} on {@code uq_connections_full_code_user_id_active}): the loser
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

    /** Open an existing instance to an agent and return the view (binding + connection). */
    @Transactional
    public AgentConnectionView bindAndView(UUID userId, UUID agentId, UUID connectionId) {
        requireOwnedAgent(userId, agentId);
        return view(agentId, requireOwnedConnection(userId, connectionId));
    }

    /**
     * Open an internal connector to an agent, naming it by code: its mode row may not exist yet, so its
     * id is unknowable to the caller until something materialises it.
     */
    @Transactional
    public AgentConnectionView bindInternalAndView(UUID userId, UUID agentId, String connectorCode) {
        requireOwnedAgent(userId, agentId);
        return view(agentId, ensureModeConnection(userId, connectorCode));
    }

    private AgentConnectionView view(UUID agentId, Connection connection) {
        AgentConnection binding = ensureBinding(agentId, connection.getId());
        return new AgentConnectionView(binding, connection,
                kindOf(connection.getConnectorCode()) == ConnectorKind.INTERNAL);
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
     * Close a connector for an agent. Any kind: skills no longer resurrect internal bindings, so the
     * user's decision is the last word — the skills that pointed at this instance simply go unsatisfied.
     */
    @Transactional
    public void unbind(UUID userId, UUID agentId, UUID connectionId) {
        requireOwnedAgent(userId, agentId);
        requireOwnedConnection(userId, connectionId);
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

    /**
     * What kind of connector a code names. {@code UNKNOWN} is a real case, not a defect guard: a skill
     * may declare a connector that has since been removed from the build, and that must read as «no
     * instance possible» rather than as an external one waiting to be chosen.
     */
    public ConnectorKind kindOf(String connectorCode) {
        return connectorRegistry.findHandler(connectorCode)
                .map(handler -> handler instanceof InternalConnectorHandler
                        ? ConnectorKind.INTERNAL : ConnectorKind.EXTERNAL)
                .orElse(ConnectorKind.UNKNOWN);
    }

    public enum ConnectorKind { INTERNAL, EXTERNAL, UNKNOWN }

    private boolean isInternal(String connectorCode) {
        return kindOf(connectorCode) == ConnectorKind.INTERNAL;
    }

    private Connection requireOwnedConnection(UUID userId, UUID connectionId) {
        return connectionRepository.findByIdAndUserIdNotDeleted(connectionId, userId)
                .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + connectionId));
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
     * Unbind an agent from every instance (deleting an agent). Bypasses {@link #unbind} because there is
     * nothing to check here: ownership is already established by the caller (the agent is loaded), and
     * a lifecycle removal takes everything regardless of who granted it.
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
