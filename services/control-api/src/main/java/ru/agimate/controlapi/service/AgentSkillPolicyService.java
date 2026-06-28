package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.manage.dto.PolicyDiffEntry;
import ru.agimate.controlapi.controller.manage.dto.PolicyDiffResponse;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Привязка скиллов к доступу агента в модели binding'ов. Скилл = набор коннекторов; «иметь скилл» =
 * агент привязан ({@code agent_connections}) к этим коннекторам, дальше дефолт-allow открывает их
 * тулы/триггеры. Контекстные коннекторы (board/memory/time) материализуются при привязке по своему
 * scope; INSTANCE-коннекторы скилл привязать не может (нужен конкретный экземпляр) — пропускаются.
 *
 * <p><b>Add-only (на ревью):</b> применение скилла гарантирует binding'и, но снятие скилла binding
 * <b>не</b> отзывает — иначе пришлось бы вести подсчёт ссылок (тот же коннектор могли включить канал
 * или вручную). Отвязка — явная. Name-гранулярность скилла (конкретный тул/триггер) пока не
 * переносится — скилл открывает коннектор целиком.
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
    private final ConnectionBindingService connectionBindingService;

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
        Set<String> desiredConnectors = desiredConnectorCodes(getCurrentSkillIds(agentId));
        Set<String> bound = boundConnectorCodes(agentId);

        int added = 0;
        for (String connectorCode : desiredConnectors) {
            if (bound.contains(connectorCode)) {
                continue;
            }
            try {
                connectionBindingService.bind(userId, agentId, connectorCode, null, null);
                added++;
            } catch (BadRequestStatusException | NotFoundStatusException e) {
                // INSTANCE-коннектор (нужен явный экземпляр), несовместимый scope или неизвестный
                // connector_code (скилл объявил коннектор, которого нет в каталоге) — пропускаем:
                // способность просто «не обеспечена», привязка скилла из-за этого падать не должна.
                log.warn("Skill cannot bind connector {} for agent {}: {}",
                        connectorCode, agentId, e.getMessage());
            }
        }
        if (added > 0) {
            log.info("Applied skill bindings for agent {}: +{} connector(s)", agentId, added);
        }
    }

    /** Diff в терминах коннекторов: что добавится. Снятие не отзывает binding (add-only), поэтому toRemove пуст. */
    private PolicyDiffResponse computeDiff(UUID agentId, Set<UUID> desiredSkillIds) {
        Set<String> desiredConnectors = desiredConnectorCodes(desiredSkillIds);
        Set<String> bound = boundConnectorCodes(agentId);

        List<PolicyDiffEntry> toAdd = desiredConnectors.stream()
                .filter(c -> !bound.contains(c))
                .sorted()
                .map(c -> new PolicyDiffEntry("CONNECTOR", c, null))
                .toList();

        return new PolicyDiffResponse(toAdd, List.of());
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

    private Set<String> boundConnectorCodes(UUID agentId) {
        List<UUID> connectionIds = agentConnectionRepository.findActiveByAgentId(agentId).stream()
                .map(AgentConnection::getConnectionId)
                .toList();
        if (connectionIds.isEmpty()) {
            return Set.of();
        }
        return connectionRepository.findByIdInNotDeleted(connectionIds).stream()
                .map(Connection::getConnectorCode)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private Set<UUID> getCurrentSkillIds(UUID agentId) {
        return agentSkillRepository.findByAgentId(agentId).stream()
                .map(AgentSkill::getSkillId)
                .collect(Collectors.toCollection(HashSet::new));
    }
}
