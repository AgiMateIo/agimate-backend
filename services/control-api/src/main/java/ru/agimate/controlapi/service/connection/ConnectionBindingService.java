package ru.agimate.controlapi.service.connection;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.UUIDUtils;
import ru.agimate.controlapi.connectors.core.events.ConnectorCreatedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorDeletedEvent;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.enums.IdentityScope;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Управление binding'ом «коннектор доступен агенту» ({@code agent_connections}) — гейт доступности.
 * Для INSTANCE-коннекторов связывает агента с уже созданным экземпляром; для контекстных
 * (память/board/time) материализует {@code connections}-запись под выбранный scope
 * (AGENT→{@code scopeId=agentId}, TEAM→{@code teamId}, USER→{@code userId}) по принципу
 * find-or-create — так несколько агентов команды разделяют одну connection (командная память).
 *
 * <p>Обобщает бывший {@code MemoryEnablementListener}: при первой материализации контекстного
 * экземпляра издаёт {@link ConnectorCreatedEvent} (регистрация джоб), при отвязке последнего агента —
 * {@link ConnectorDeletedEvent} (снятие джоб).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConnectionBindingService {

    private final ConnectorRepository connectorRepository;
    private final ConnectionRepository connectionRepository;
    private final AgentConnectionRepository agentConnectionRepository;
    private final AgentRepository agentRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Привязать коннектор к агенту. {@code requestedScope} — выбранный scope (null = дефолт коннектора);
     * {@code explicitConnectionId} обязателен для INSTANCE-коннекторов (какой именно экземпляр).
     */
    @Transactional
    public AgentConnection bind(UUID userId, UUID agentId, String connectorCode,
                                IdentityScope requestedScope, UUID explicitConnectionId) {
        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));

        IdentityScope scope = requestedScope != null ? requestedScope : connector.resolveDefaultScope();
        if (scope == null || !connector.supportsScope(scope)) {
            throw new BadRequestStatusException(
                    "Connector " + connectorCode + " does not support scope " + scope);
        }

        Connection connection = resolveConnection(userId, agentId, connector, scope, explicitConnectionId);
        return ensureBinding(agentId, connection.getId());
    }

    /** Binding вместе с его connection — для manage-API (список/ответ на привязку). */
    public record AgentConnectionView(AgentConnection binding, Connection connection) {}

    /** Активные binding'и агента с их connection (для manage-листинга). */
    public List<AgentConnectionView> listForAgent(UUID userId, UUID agentId) {
        requireOwnedAgent(userId, agentId);
        List<AgentConnectionView> views = new ArrayList<>();
        for (AgentConnection b : agentConnectionRepository.findActiveByAgentId(agentId)) {
            connectionRepository.findByIdNotDeleted(b.getConnectionId())
                    .ifPresent(c -> views.add(new AgentConnectionView(b, c)));
        }
        return views;
    }

    /** Привязать и вернуть view (binding + connection) — для ответа manage-API. */
    @Transactional
    public AgentConnectionView bindAndView(UUID userId, UUID agentId, String connectorCode,
                                           IdentityScope requestedScope, UUID explicitConnectionId) {
        requireOwnedAgent(userId, agentId);
        AgentConnection binding = bind(userId, agentId, connectorCode, requestedScope, explicitConnectionId);
        Connection connection = connectionRepository.findByIdNotDeleted(binding.getConnectionId())
                .orElseThrow(() -> new NotFoundStatusException("Connection not found"));
        return new AgentConnectionView(binding, connection);
    }

    private void requireOwnedAgent(UUID userId, UUID agentId) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found: " + agentId));
        if (!agent.getUserId().equals(userId)) {
            throw new NotFoundStatusException("Agent not found: " + agentId);
        }
    }

    /**
     * Привязать агента к уже существующему экземпляру (по его id). Для случаев, где connection
     * уже выбрана (канал на конкретный telegram/mcp/app) — scope-материализация не нужна.
     */
    @Transactional
    public AgentConnection ensureBindingToExisting(UUID userId, UUID agentId, UUID connectionId) {
        connectionRepository.findByIdAndUserIdNotDeleted(connectionId, userId)
                .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + connectionId));
        return ensureBinding(agentId, connectionId);
    }

    /** Отвязать; контекстный экземпляр без оставшихся binding'ов сворачивается (снятие джоб). */
    @Transactional
    public void unbind(UUID userId, UUID agentId, UUID connectionId) {
        AgentConnection binding = agentConnectionRepository.findActiveBinding(agentId, connectionId)
                .orElseThrow(() -> new NotFoundStatusException("Binding not found"));
        LocalDateTime now = LocalDateTime.now();
        agentConnectionRepository.softDelete(binding.getId(), now);

        Connection connection = connectionRepository.findByIdNotDeleted(connectionId).orElse(null);
        if (connection == null || connection.getIdentityScope() == IdentityScope.INSTANCE) {
            return; // INSTANCE живёт независимо от binding'ов (управляется как экземпляр)
        }
        if (agentConnectionRepository.findActiveByConnectionId(connectionId).isEmpty()) {
            connectionRepository.softDelete(connectionId, now);
            eventPublisher.publishEvent(new ConnectorDeletedEvent(
                    connection.getConnectorCode(), connectionId.toString()));
            log.info("Context connection {} ({}) collapsed — no bindings left",
                    connectionId, connection.getConnectorCode());
        }
    }

    private Connection resolveConnection(UUID userId, UUID agentId, Connector connector,
                                         IdentityScope scope, UUID explicitConnectionId) {
        if (scope == IdentityScope.INSTANCE) {
            if (explicitConnectionId == null) {
                throw new BadRequestStatusException(
                        "INSTANCE connector requires an explicit connectionId to bind");
            }
            return connectionRepository.findByIdAndUserIdNotDeleted(explicitConnectionId, userId)
                    .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + explicitConnectionId));
        }

        UUID scopeId = scopeIdFor(scope, userId, agentId);
        return connectionRepository.findActiveByConnectorCodeAndScopeId(connector.getCode(), scopeId)
                .orElseGet(() -> materializeContextConnection(userId, connector, scope, scopeId));
    }

    private UUID scopeIdFor(IdentityScope scope, UUID userId, UUID agentId) {
        return switch (scope) {
            case AGENT -> agentId;
            case USER -> userId;
            case TEAM -> {
                Agent agent = agentRepository.findById(agentId)
                        .orElseThrow(() -> new NotFoundStatusException("Agent not found: " + agentId));
                if (agent.getAgenticTeamId() == null) {
                    throw new BadRequestStatusException("Agent has no team — TEAM scope unavailable");
                }
                yield agent.getAgenticTeamId();
            }
            case GLOBAL, INSTANCE -> throw new BadRequestStatusException(
                    "Scope " + scope + " is not materializable by binding");
        };
    }

    private Connection materializeContextConnection(UUID userId, Connector connector,
                                                    IdentityScope scope, UUID scopeId) {
        // full_code уникален per (user, scope): для контекстных синглтонов добавляем scopeId,
        // иначе несколько AGENT-экземпляров одного пользователя нарушат uq_connections_full_code_user.
        String fullCode = connector.getCode() + "_" + scopeId;
        Connection connection = connectionRepository.save(Connection.builder()
                .id(UUIDUtils.generateUUIDv8())
                .connectorCode(connector.getCode())
                .subCode(null)
                .fullCode(fullCode)
                .userId(userId)
                .identityScope(scope)
                .scopeId(scopeId)
                .name(connector.getName())
                .enabled(true)
                .build());

        eventPublisher.publishEvent(new ConnectorCreatedEvent(
                connector.getCode(), connection.getId().toString(), userId));
        log.info("Materialized {} connection {} (scope={}, scopeId={})",
                connector.getCode(), connection.getId(), scope, scopeId);
        return connection;
    }

    private AgentConnection ensureBinding(UUID agentId, UUID connectionId) {
        return agentConnectionRepository.findActiveBinding(agentId, connectionId)
                .orElseGet(() -> agentConnectionRepository.save(AgentConnection.builder()
                        .agentId(agentId)
                        .connectionId(connectionId)
                        .build()));
    }
}
