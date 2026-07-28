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
 * Binding of skills to an agent's access. A skill is a set of connectors; «having a skill» means the
 * agent is bound ({@code agent_connections}) to those connectors' mode rows, after which default-allow
 * opens their tools and triggers.
 *
 * <p><b>Reconciliation:</b> skills are the source of truth for internal connectors' bindings. The sync
 * adds the missing bindings and removes the superfluous ones — internal ones required by none of the
 * current skills. Bindings held by an active channel (webchat/acp — created by the channel services)
 * and external instances (telegram/mcp/app, managed explicitly) are left alone by the sync. An external
 * connector declared by a skill cannot be bound (a concrete instance is required) — it is skipped with
 * a warning.
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
            // An external connector (which needs an explicit instance) or an unknown connector_code (the skill
            // declared a connector that does not exist) — the capability is simply «not provided», and binding the
            // skill must not fail because of it. We check up front rather than catching an exception out of
            // bindInternal: its transaction participates in ours, and its rollback would mark the outer one
            // rollback-only — a swallowed exception would then surface as UnexpectedRollbackException at commit.
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
     * Whether a binding is removed during reconciliation: an internal connector not required by the
     * current skills and not held by an active channel (webchat/acp create the binding together with the
     * channel — the channel is precisely the sign of «this binding did not come from a skill»).
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

    /** The diff in terms of connectors: what reconciliation will bind and what it will remove. */
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

    /** An agent's active bindings, keyed by the connector code of their connection. */
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
