package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.deviceapi.abac.AccessEffect;
import ru.agimate.deviceapi.abac.ToolPolicyDbEvaluatorService;
import ru.agimate.deviceapi.abac.TriggerPolicyDbEvaluatorService;
import ru.agimate.deviceapi.controller.manage.dto.PolicyDiffEntry;
import ru.agimate.deviceapi.controller.manage.dto.PolicyDiffResponse;
import ru.agimate.deviceapi.database.entities.AgentSkill;
import ru.agimate.deviceapi.database.entities.AgentToolPolicy;
import ru.agimate.deviceapi.database.entities.AgentTriggerPolicy;
import ru.agimate.deviceapi.database.entities.SkillConnector;
import ru.agimate.deviceapi.database.enums.SkillConnectorType;
import ru.agimate.deviceapi.database.repositories.AgentSkillRepository;
import ru.agimate.deviceapi.database.repositories.AgentToolPolicyRepository;
import ru.agimate.deviceapi.database.repositories.AgentTriggerPolicyRepository;
import ru.agimate.deviceapi.database.repositories.SkillConnectorRepository;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentSkillPolicyService {

    private static final String SOURCE_SKILL = "skill";

    private final AgentSkillRepository agentSkillRepository;
    private final SkillConnectorRepository skillConnectorRepository;
    private final AgentToolPolicyRepository agentToolPolicyRepository;
    private final AgentTriggerPolicyRepository agentTriggerPolicyRepository;
    private final ToolPolicyDbEvaluatorService toolPolicyEvaluatorService;
    private final TriggerPolicyDbEvaluatorService triggerPolicyEvaluatorService;

    public PolicyDiffResponse previewAdd(UUID agentId, UUID skillId) {
        Set<UUID> desiredSkillIds = getCurrentSkillIds(agentId);
        desiredSkillIds.add(skillId);
        return computeDiff(agentId, desiredSkillIds);
    }

    public PolicyDiffResponse previewRemove(UUID agentId, UUID skillId) {
        Set<UUID> desiredSkillIds = getCurrentSkillIds(agentId);
        desiredSkillIds.remove(skillId);
        return computeDiff(agentId, desiredSkillIds);
    }

    public PolicyDiffResponse previewSync(UUID agentId) {
        Set<UUID> desiredSkillIds = getCurrentSkillIds(agentId);
        return computeDiff(agentId, desiredSkillIds);
    }

    @Transactional
    public void applyDiff(UUID agentId, UUID userId) {
        Set<UUID> desiredSkillIds = getCurrentSkillIds(agentId);
        PolicyDiffResponse diff = computeDiff(agentId, desiredSkillIds);
        executeDiff(agentId, userId, diff);
    }

    private PolicyDiffResponse computeDiff(UUID agentId, Set<UUID> desiredSkillIds) {
        // Build desired policy set from skill connectors
        Set<PolicyKey> desiredPolicies = buildDesiredPolicies(desiredSkillIds);

        // Load existing source="skill" policies
        Set<PolicyKey> existingSkillPolicies = loadExistingSkillPolicies(agentId);

        // Also load ALL existing policies to avoid duplicating manual ones
        Set<PolicyKey> allExistingPolicies = loadAllExistingAllowPolicies(agentId);

        // To add: desired but not yet existing (among ALL policies)
        List<PolicyDiffEntry> toAdd = desiredPolicies.stream()
                .filter(p -> !allExistingPolicies.contains(p))
                .map(PolicyKey::toEntry)
                .sorted(Comparator.comparing(PolicyDiffEntry::policyType)
                        .thenComparing(PolicyDiffEntry::connectorCode, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PolicyDiffEntry::name, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        // To remove: existing source="skill" policies not in desired set
        List<PolicyDiffEntry> toRemove = existingSkillPolicies.stream()
                .filter(p -> !desiredPolicies.contains(p))
                .map(PolicyKey::toEntry)
                .sorted(Comparator.comparing(PolicyDiffEntry::policyType)
                        .thenComparing(PolicyDiffEntry::connectorCode, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PolicyDiffEntry::name, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        return new PolicyDiffResponse(toAdd, toRemove);
    }

    private void executeDiff(UUID agentId, UUID userId, PolicyDiffResponse diff) {
        for (PolicyDiffEntry entry : diff.policiesToAdd()) {
            createPolicy(agentId, userId, entry);
        }

        for (PolicyDiffEntry entry : diff.policiesToRemove()) {
            removePolicy(agentId, entry);
        }

        if (!diff.policiesToAdd().isEmpty() || !diff.policiesToRemove().isEmpty()) {
            toolPolicyEvaluatorService.invalidateByAgent(agentId);
            triggerPolicyEvaluatorService.invalidateByAgent(agentId);
            log.info("Applied policy diff for agent {}: +{} -{}", agentId,
                    diff.policiesToAdd().size(), diff.policiesToRemove().size());
        }
    }

    private void createPolicy(UUID agentId, UUID userId, PolicyDiffEntry entry) {
        if ("TOOL".equals(entry.policyType())) {
            AgentToolPolicy policy = AgentToolPolicy.builder()
                    .userId(userId)
                    .agentId(agentId)
                    .connectorCode(entry.connectorCode())
                    .toolName(entry.name())
                    .effect(AccessEffect.ALLOW)
                    .source(SOURCE_SKILL)
                    .description("Auto-managed by skill bindings")
                    .build();
            agentToolPolicyRepository.save(policy);
        } else {
            AgentTriggerPolicy policy = AgentTriggerPolicy.builder()
                    .userId(userId)
                    .agentId(agentId)
                    .connectorCode(entry.connectorCode())
                    .triggerName(entry.name())
                    .effect(AccessEffect.ALLOW)
                    .source(SOURCE_SKILL)
                    .description("Auto-managed by skill bindings")
                    .build();
            agentTriggerPolicyRepository.save(policy);
        }
    }

    private void removePolicy(UUID agentId, PolicyDiffEntry entry) {
        if ("TOOL".equals(entry.policyType())) {
            AgentToolPolicy policy = agentToolPolicyRepository.findByCompositeKey(
                    agentId, entry.connectorCode(), null, entry.name(), AccessEffect.ALLOW.name());
            if (policy != null && SOURCE_SKILL.equals(policy.getSource())) {
                agentToolPolicyRepository.delete(policy);
            }
        } else {
            AgentTriggerPolicy policy = agentTriggerPolicyRepository.findByCompositeKey(
                    agentId, entry.connectorCode(), null, entry.name(), AccessEffect.ALLOW.name());
            if (policy != null && SOURCE_SKILL.equals(policy.getSource())) {
                agentTriggerPolicyRepository.delete(policy);
            }
        }
    }

    private Set<PolicyKey> buildDesiredPolicies(Set<UUID> skillIds) {
        if (skillIds.isEmpty()) {
            return Set.of();
        }

        List<SkillConnector> connectors = skillConnectorRepository.findBySkillIdIn(skillIds);
        Set<PolicyKey> desired = new HashSet<>();

        for (SkillConnector sc : connectors) {
            // type=null means connector-level access — create both TOOL and TRIGGER policies
            if (sc.getType() == SkillConnectorType.TOOL || sc.getType() == null) {
                desired.add(new PolicyKey("TOOL", sc.getConnectorCode(), sc.getName()));
            }
            if (sc.getType() == SkillConnectorType.TRIGGER || sc.getType() == null) {
                desired.add(new PolicyKey("TRIGGER", sc.getConnectorCode(), sc.getName()));
            }
        }

        return desired;
    }

    private Set<PolicyKey> loadExistingSkillPolicies(UUID agentId) {
        Set<PolicyKey> existing = new HashSet<>();

        agentToolPolicyRepository.findByAgentIdAndSource(agentId, SOURCE_SKILL)
                .forEach(p -> existing.add(new PolicyKey("TOOL", p.getConnectorCode(), p.getToolName())));

        agentTriggerPolicyRepository.findByAgentIdAndSource(agentId, SOURCE_SKILL)
                .forEach(p -> existing.add(new PolicyKey("TRIGGER", p.getConnectorCode(), p.getTriggerName())));

        return existing;
    }

    private Set<PolicyKey> loadAllExistingAllowPolicies(UUID agentId) {
        Set<PolicyKey> existing = new HashSet<>();

        agentToolPolicyRepository.findByAgentId(agentId).stream()
                .filter(p -> p.getEffect() == AccessEffect.ALLOW)
                .forEach(p -> existing.add(new PolicyKey("TOOL", p.getConnectorCode(), p.getToolName())));

        agentTriggerPolicyRepository.findByAgentId(agentId).stream()
                .filter(p -> p.getEffect() == AccessEffect.ALLOW)
                .forEach(p -> existing.add(new PolicyKey("TRIGGER", p.getConnectorCode(), p.getTriggerName())));

        return existing;
    }

    private Set<UUID> getCurrentSkillIds(UUID agentId) {
        return agentSkillRepository.findByAgentId(agentId).stream()
                .map(AgentSkill::getSkillId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private record PolicyKey(String policyType, String connectorCode, String name) {
        PolicyDiffEntry toEntry() {
            return new PolicyDiffEntry(policyType, connectorCode, name);
        }
    }
}
