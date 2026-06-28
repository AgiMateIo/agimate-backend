package ru.agimate.controlapi.service.connection;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.UUIDUtils;
import ru.agimate.controlapi.abac.ConnectionAccessEvaluator;
import ru.agimate.controlapi.connectors.core.events.ConnectorCreatedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorDeletedEvent;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.enums.IdentityScope;
import ru.agimate.controlapi.database.repositories.AgentConnectionPolicyRepository;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final AgentConnectionPolicyRepository policyRepository;
    private final AgentRepository agentRepository;
    private final ConnectionAccessEvaluator accessEvaluator;
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
        if (scope != IdentityScope.INSTANCE) {
            // Контекстный (scoped) коннектор — у агента он один: нельзя иметь и личную, и командную
            // память одновременно (иначе gRPC GetMemory неоднозначен). INSTANCE (telegram/mcp) — можно много.
            requireNoOtherContextBinding(agentId, connector.getCode(), connection.getId());
        }
        return ensureBinding(agentId, connection.getId());
    }

    /** Бросает, если у агента уже есть binding на этот connector_code через ДРУГУЮ connection. */
    private void requireNoOtherContextBinding(UUID agentId, String connectorCode, UUID targetConnectionId) {
        for (AgentConnection b : agentConnectionRepository.findActiveByAgentId(agentId)) {
            if (b.getConnectionId().equals(targetConnectionId)) {
                continue; // та же connection — идемпотентная повторная привязка
            }
            connectionRepository.findByIdNotDeleted(b.getConnectionId())
                    .filter(c -> connectorCode.equals(c.getConnectorCode()))
                    .ifPresent(c -> {
                        throw new BadRequestStatusException("Agent is already bound to " + connectorCode
                                + " with a different scope — unbind it first");
                    });
        }
    }

    /** Binding вместе с его connection — для manage-API (список/ответ на привязку). */
    public record AgentConnectionView(AgentConnection binding, Connection connection) {}

    /** Активные binding'и агента с их connection (для manage-листинга). */
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
                views.add(new AgentConnectionView(b, c));
            }
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
        // Снимаем правила этого binding'а — иначе при ре-привязке (новый binding id) они осиротеют.
        policyRepository.softDeleteByAgentConnectionId(binding.getId(), now);
        // Сбрасываем кэш решений, чтобы отзыв применился сразу (а не через TTL).
        invalidate(agentId, connectionId);

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
                .map(c -> requireOwner(c, userId))
                .orElseGet(() -> materializeOrReread(userId, connector, scope, scopeId));
    }

    /**
     * Defense-in-depth: найденная по {@code (connector_code, scope_id)} connection обязана принадлежать
     * вызывающему. Сейчас недостижимо (scope_id выводится из owned-сущностей), но страхует от регрессий,
     * если когда-нибудь появится смена команды агента.
     */
    private Connection requireOwner(Connection connection, UUID userId) {
        if (!connection.getUserId().equals(userId)) {
            throw new NotFoundStatusException("Connection not found");
        }
        return connection;
    }

    /** Материализация с обработкой гонки: параллельный bind того же scope → перечитываем существующую. */
    private Connection materializeOrReread(UUID userId, Connector connector, IdentityScope scope, UUID scopeId) {
        try {
            return materializeContextConnection(userId, connector, scope, scopeId);
        } catch (DataIntegrityViolationException e) {
            return connectionRepository.findActiveByConnectorCodeAndScopeId(connector.getCode(), scopeId)
                    .map(c -> requireOwner(c, userId))
                    .orElseThrow(() -> e);
        }
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
        AgentConnection binding = agentConnectionRepository.findActiveBinding(agentId, connectionId)
                .orElseGet(() -> saveBinding(agentId, connectionId));
        // Свежий binding мог иметь закэшированный deny — сбрасываем, чтобы доступ применился сразу.
        invalidate(agentId, connectionId);
        return binding;
    }

    private AgentConnection saveBinding(UUID agentId, UUID connectionId) {
        try {
            return agentConnectionRepository.save(AgentConnection.builder()
                    .agentId(agentId)
                    .connectionId(connectionId)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // Гонка против uq_agent_connections_active — перечитываем созданный параллельно binding.
            return agentConnectionRepository.findActiveBinding(agentId, connectionId).orElseThrow(() -> e);
        }
    }

    private void invalidate(UUID agentId, UUID connectionId) {
        accessEvaluator.invalidateByAgent(agentId);
        accessEvaluator.invalidateByConnection(connectionId);
    }
}
