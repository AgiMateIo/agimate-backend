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
 * Управление доступностью коннекторов агентам ({@code agent_connections}).
 *
 * <p>Внутренние коннекторы (board/memory/time/media/webchat/acp): connection — строка-режим,
 * <b>одна на пользователя</b> ({@link #ensureModeConnection}, find-or-create по
 * {@code (connector_code, user_id)}). Владельца данных код коннектора резолвит в момент вызова из
 * {@code ConnectorEnv} (правило — знание коннектора, см. docs/connectors/architecture.md). Доступ агентам
 * выдаёт скилл-синк ({@code AgentSkillPolicyService}) или канальные сервисы (webchat/acp) через
 * {@link #bindInternal}; ручное управление привязками внутренних коннекторов через manage-API
 * запрещено.
 *
 * <p>Внешние коннекторы (telegram/mcp/app): connection = конкретный экземпляр с кредами, привязка —
 * только к существующему id ({@link #ensureBindingToExisting}).
 *
 * <p>При первой материализации строки-режима издаёт {@link ConnectorCreatedEvent} (регистрация
 * декларативных {@code @Job}); строка живёт дальше независимо от привязок — collapse нет.
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

    /** Открыть агенту внутренний коннектор: строка-режим пользователя + {@code agent_connection}. */
    @Transactional
    public AgentConnection bindInternal(UUID userId, UUID agentId, String connectorCode) {
        Connection connection = ensureModeConnection(userId, connectorCode);
        return ensureBinding(agentId, connection.getId());
    }

    /** Строка-режим внутреннего коннектора (одна на пользователя): find-or-create. */
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
     * Материализация строки-режима. Гонку параллельного создания решает БД
     * ({@code INSERT … ON CONFLICT DO NOTHING} по {@code uq_connections_full_code_user}):
     * проигравший дождётся коммита победителя и перечитает его строку — без исключений и
     * без отравления текущей транзакции.
     */
    private Connection createModeConnection(UUID userId, ConnectorHandler handler) {
        String connectorCode = handler.connectorCode();
        UUID id = UUIDUtils.generateUUIDv8();
        int inserted = connectionRepository.insertModeConnectionIfAbsent(
                id, connectorCode, connectorCode + "_" + userId, userId,
                connectorTexts.name(connectorCode, handler.connectorName()));
        if (inserted > 0) {
            // Событие только от фактического создателя строки (регистрация джоб — AFTER_COMMIT).
            eventPublisher.publishEvent(new ConnectorCreatedEvent(connectorCode, id.toString(), userId));
            log.info("Materialized {} mode connection {} for user {}", connectorCode, id, userId);
        }
        return connectionRepository.findByUserIdAndConnectorCodeNotDeleted(userId, connectorCode).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Mode connection missing right after materialization: " + connectorCode));
    }

    /** Binding вместе с его connection — для manage-API (список/ответ на привязку). */
    public record AgentConnectionView(AgentConnection binding, Connection connection) {}

    /** Активные привязки агента с их connection (для manage-листинга). */
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

    /** Binding вместе с его агентом — обратный листинг «кто использует экземпляр». */
    public record ConnectionAgentView(AgentConnection binding, Agent agent) {}

    /**
     * Активные привязки connection с их агентами. Отключённые агенты остаются в выдаче: это
     * инвентарь использования экземпляра (кого затронет удаление/смена кредов), а не список
     * получателей триггера — там свой фильтр {@code AgentRepository.findBoundToConnection}.
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
            // Агента могли удалить мягко (@SQLRestriction его уже не отдаёт), binding при этом жив.
            Agent agent = byId.get(b.getAgentId());
            if (agent != null) {
                views.add(new ConnectionAgentView(b, agent));
            }
        }
        return views;
    }

    /** Привязать внешний экземпляр и вернуть view (binding + connection) — для ответа manage-API. */
    @Transactional
    public AgentConnectionView bindAndView(UUID userId, UUID agentId, UUID connectionId) {
        requireOwnedAgent(userId, agentId);
        Connection connection = requireExternalConnection(userId, connectionId);
        AgentConnection binding = ensureBinding(agentId, connection.getId());
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
     * уже выбрана (канал на конкретный telegram/mcp/app).
     */
    @Transactional
    public AgentConnection ensureBindingToExisting(UUID userId, UUID agentId, UUID connectionId) {
        connectionRepository.findByIdAndUserIdNotDeleted(connectionId, userId)
                .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + connectionId));
        return ensureBinding(agentId, connectionId);
    }

    /**
     * Отвязать внешний экземпляр от агента. Привязки внутренних коннекторов управляются
     * скилл-синком/каналами — ручная отвязка запрещена (иначе следующий синк её воскресит).
     */
    @Transactional
    public void unbind(UUID userId, UUID agentId, UUID connectionId) {
        requireOwnedAgent(userId, agentId);
        requireExternalConnection(userId, connectionId);
        AgentConnection binding = agentConnectionRepository.findActiveBinding(agentId, connectionId)
                .orElseThrow(() -> new NotFoundStatusException("Binding not found"));
        removeBinding(binding);
    }

    /** Снять привязку + её политики + кэш решений. Общий низ для unbind и скилл-реконсиляции. */
    @Transactional
    public void removeBinding(AgentConnection binding) {
        LocalDateTime now = LocalDateTime.now();
        agentConnectionRepository.softDelete(binding.getId(), now);
        // Снимаем правила этого binding'а — иначе при ре-привязке (новый binding id) они осиротеют.
        policyRepository.softDeleteByAgentConnectionId(binding.getId(), now);
        // Сбрасываем кэш решений, чтобы отзыв применился сразу (а не через TTL).
        invalidate(binding.getAgentId(), binding.getConnectionId());
    }

    private Connection requireExternalConnection(UUID userId, UUID connectionId) {
        Connection connection = connectionRepository.findByIdAndUserIdNotDeleted(connectionId, userId)
                .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + connectionId));
        boolean internal = connectorRegistry.findHandler(connection.getConnectorCode())
                .map(InternalConnectorHandler.class::isInstance)
                .orElse(false);
        if (internal) {
            throw new BadRequestStatusException(
                    "Connector " + connection.getConnectorCode() + " is managed by skills");
        }
        return connection;
    }

    /**
     * Отвязать connection от всех агентов (например, при удалении App-экземпляра): soft-delete всех
     * активных binding'ов + их политик + сброс кэша решений. Сам экземпляр connection не трогаем — его
     * жизненным циклом управляет владелец (AppService удаляет connection отдельно).
     */
    @Transactional
    public void detachConnection(UUID connectionId) {
        for (AgentConnection binding : agentConnectionRepository.findActiveByConnectionId(connectionId)) {
            removeBinding(binding);
        }
    }

    /** Идемпотентно: гонку параллельной привязки решает БД (ON CONFLICT), затем перечитываем строку. */
    private AgentConnection ensureBinding(UUID agentId, UUID connectionId) {
        agentConnectionRepository.insertBindingIfAbsent(agentId, connectionId);
        AgentConnection binding = agentConnectionRepository.findActiveBinding(agentId, connectionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Binding missing right after insert: agent=" + agentId + ", connection=" + connectionId));
        // Свежий binding мог иметь закэшированный deny — сбрасываем, чтобы доступ применился сразу.
        invalidate(agentId, connectionId);
        return binding;
    }

    private void invalidate(UUID agentId, UUID connectionId) {
        accessEvaluator.invalidateByAgent(agentId);
        accessEvaluator.invalidateByConnection(connectionId);
    }
}
