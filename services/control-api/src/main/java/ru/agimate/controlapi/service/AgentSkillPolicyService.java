package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.controller.manage.dto.PolicyDiffEntry;
import ru.agimate.controlapi.controller.manage.dto.PolicyDiffResponse;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Привязка скиллов к доступу агента. Скилл = набор коннекторов; «иметь скилл» = агент привязан
 * ({@code agent_connections}) к строкам-режимам этих коннекторов, дальше дефолт-allow открывает их
 * тулы/триггеры.
 *
 * <p><b>Реконсиляция:</b> скиллы — источник истины для привязок внутренних коннекторов. Синк
 * добавляет недостающие привязки и снимает лишние — внутренние, не требуемые ни одним текущим
 * скиллом. Привязки, удерживаемые активным каналом (webchat/acp — их создают канальные сервисы),
 * и внешние экземпляры (telegram/mcp/app, управляются явно) синк не трогает. Внешний коннектор,
 * объявленный скиллом, привязать нельзя (нужен конкретный экземпляр) — пропускается с warn.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentSkillPolicyService {

    private final AgentSkillRepository agentSkillRepository;
    private final SkillRepository skillRepository;
    private final AgentConnectionRepository agentConnectionRepository;
    private final ConnectionRepository connectionRepository;
    private final ChannelRepository channelRepository;
    private final ConnectionBindingService connectionBindingService;
    private final ConnectorRegistry connectorRegistry;

    public PolicyDiffResponse previewAdd(UUID agentId, UUID skillId) {
        Set<UUID> desired = getCurrentSkillIds(agentId);
        desired.add(skillId);
        return computeDiff(agentId, desired);
    }

    public PolicyDiffResponse previewRemove(UUID agentId, UUID skillId) {
        Set<UUID> desired = getCurrentSkillIds(agentId);
        desired.remove(skillId);
        return computeDiff(agentId, desired);
    }

    public PolicyDiffResponse previewSync(UUID agentId) {
        return computeDiff(agentId, getCurrentSkillIds(agentId));
    }

    @Transactional
    public void applyDiff(UUID agentId, UUID userId) {
        Set<String> desired = desiredConnectorCodes(getCurrentSkillIds(agentId));
        Map<String, AgentConnection> bound = boundByConnectorCode(agentId);

        int added = 0;
        for (String connectorCode : desired) {
            if (bound.containsKey(connectorCode)) {
                continue;
            }
            // Внешний коннектор (нужен явный экземпляр) или неизвестный connector_code (скилл
            // объявил коннектор, которого нет) — способность просто «не обеспечена», привязка
            // скилла из-за этого падать не должна. Проверяем заранее, а не ловим исключение из
            // bindInternal: его транзакция участвует в нашей, и её откат пометил бы общую
            // rollback-only — проглоченное исключение всплыло бы UnexpectedRollbackException на коммите.
            if (!isInternal(connectorCode)) {
                log.warn("Skill cannot bind connector {} for agent {}: not an internal connector",
                        connectorCode, agentId);
                continue;
            }
            connectionBindingService.bindInternal(userId, agentId, connectorCode);
            added++;
        }

        int removed = 0;
        for (Map.Entry<String, AgentConnection> e : bound.entrySet()) {
            if (isRevokable(agentId, e.getKey(), e.getValue(), desired)) {
                connectionBindingService.removeBinding(e.getValue());
                removed++;
            }
        }

        if (added > 0 || removed > 0) {
            log.info("Reconciled skill bindings for agent {}: +{} / -{} connector(s)",
                    agentId, added, removed);
        }
    }

    /**
     * Снимается ли привязка при реконсиляции: внутренний коннектор, не требуемый текущими скиллами
     * и не удерживаемый активным каналом (webchat/acp создают привязку вместе с каналом — канал и
     * есть признак «привязка не от скилла»).
     */
    private boolean isRevokable(UUID agentId, String connectorCode, AgentConnection binding,
                                Set<String> desired) {
        if (desired.contains(connectorCode) || !isInternal(connectorCode)) {
            return false;
        }
        return channelRepository.findByAgentIdAndConnectorCodeAndConnectionIdAndDeletedAtIsNull(
                agentId, connectorCode, binding.getConnectionId()).isEmpty();
    }

    private boolean isInternal(String connectorCode) {
        return connectorRegistry.findHandler(connectorCode)
                .map(InternalConnectorHandler.class::isInstance)
                .orElse(false);
    }

    /** Diff в терминах коннекторов: что привяжется и что будет снято реконсиляцией. */
    private PolicyDiffResponse computeDiff(UUID agentId, Set<UUID> desiredSkillIds) {
        Set<String> desired = desiredConnectorCodes(desiredSkillIds);
        Map<String, AgentConnection> bound = boundByConnectorCode(agentId);

        List<PolicyDiffEntry> toAdd = desired.stream()
                .filter(c -> !bound.containsKey(c))
                .sorted()
                .map(c -> new PolicyDiffEntry("CONNECTOR", c, null))
                .toList();

        List<PolicyDiffEntry> toRemove = new ArrayList<>();
        for (Map.Entry<String, AgentConnection> e : bound.entrySet()) {
            if (isRevokable(agentId, e.getKey(), e.getValue(), desired)) {
                toRemove.add(new PolicyDiffEntry("CONNECTOR", e.getKey(), null));
            }
        }
        toRemove.sort(Comparator.comparing(PolicyDiffEntry::connectorCode));

        return new PolicyDiffResponse(toAdd, toRemove);
    }

    private Set<String> desiredConnectorCodes(Set<UUID> skillIds) {
        if (skillIds.isEmpty()) {
            return Set.of();
        }
        return skillRepository.findByIdInNotDeleted(skillIds).stream()
                .map(Skill::getConnectorCodes)
                .flatMap(List::stream)
                .collect(Collectors.toCollection(HashSet::new));
    }

    /** Активные привязки агента по коду коннектора их connection. */
    private Map<String, AgentConnection> boundByConnectorCode(UUID agentId) {
        List<AgentConnection> bindings = agentConnectionRepository.findActiveByAgentId(agentId);
        if (bindings.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Connection> connections = connectionRepository.findByIdInNotDeleted(
                        bindings.stream().map(AgentConnection::getConnectionId).toList()).stream()
                .collect(Collectors.toMap(Connection::getId, Function.identity()));
        return bindings.stream()
                .filter(b -> connections.containsKey(b.getConnectionId()))
                .collect(Collectors.toMap(
                        b -> connections.get(b.getConnectionId()).getConnectorCode(),
                        Function.identity(),
                        (a, b) -> a));
    }

    private Set<UUID> getCurrentSkillIds(UUID agentId) {
        return agentSkillRepository.findByAgentId(agentId).stream()
                .map(AgentSkill::getSkillId)
                .collect(Collectors.toCollection(HashSet::new));
    }
}
