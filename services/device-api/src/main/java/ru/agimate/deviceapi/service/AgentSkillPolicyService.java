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

    public PolicyDiffResponse previewAdd(UUID agentPubId, UUID skillPubId) {
        Set<UUID> desiredSkillPubIds = getCurrentSkillPubIds(agentPubId);
        desiredSkillPubIds.add(skillPubId);
        return computeDiff(agentPubId, desiredSkillPubIds);
    }

    public PolicyDiffResponse previewRemove(UUID agentPubId, UUID skillPubId) {
        Set<UUID> desiredSkillPubIds = getCurrentSkillPubIds(agentPubId);
        desiredSkillPubIds.remove(skillPubId);
        return computeDiff(agentPubId, desiredSkillPubIds);
    }

    public PolicyDiffResponse previewSync(UUID agentPubId) {
        Set<UUID> desiredSkillPubIds = getCurrentSkillPubIds(agentPubId);
        return computeDiff(agentPubId, desiredSkillPubIds);
    }

    @Transactional
    public void applyDiff(UUID agentPubId, UUID userPubId) {
        Set<UUID> desiredSkillPubIds = getCurrentSkillPubIds(agentPubId);
        PolicyDiffResponse diff = computeDiff(agentPubId, desiredSkillPubIds);
        executeDiff(agentPubId, userPubId, diff);
    }

    private PolicyDiffResponse computeDiff(UUID agentPubId, Set<UUID> desiredSkillPubIds) {
        // Build desired policy set from skill connectors
        Set<PolicyKey> desiredPolicies = buildDesiredPolicies(desiredSkillPubIds);

        // Load existing source="skill" policies
        Set<PolicyKey> existingSkillPolicies = loadExistingSkillPolicies(agentPubId);

        // Also load ALL existing policies to avoid duplicating manual ones
        Set<PolicyKey> allExistingPolicies = loadAllExistingAllowPolicies(agentPubId);

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

    private void executeDiff(UUID agentPubId, UUID userPubId, PolicyDiffResponse diff) {
        for (PolicyDiffEntry entry : diff.policiesToAdd()) {
            createPolicy(agentPubId, userPubId, entry);
        }

        for (PolicyDiffEntry entry : diff.policiesToRemove()) {
            removePolicy(agentPubId, entry);
        }

        if (!diff.policiesToAdd().isEmpty() || !diff.policiesToRemove().isEmpty()) {
            toolPolicyEvaluatorService.invalidateByAgent(agentPubId);
            triggerPolicyEvaluatorService.invalidateByAgent(agentPubId);
            log.info("Applied policy diff for agent {}: +{} -{}", agentPubId,
                    diff.policiesToAdd().size(), diff.policiesToRemove().size());
        }
    }

    private void createPolicy(UUID agentPubId, UUID userPubId, PolicyDiffEntry entry) {
        if ("TOOL".equals(entry.policyType())) {
            AgentToolPolicy policy = AgentToolPolicy.builder()
                    .userPubId(userPubId)
                    .agentPubId(agentPubId)
                    .connectorCode(entry.connectorCode())
                    .toolName(entry.name())
                    .effect(AccessEffect.ALLOW)
                    .source(SOURCE_SKILL)
                    .description("Auto-managed by skill bindings")
                    .build();
            agentToolPolicyRepository.save(policy);
        } else {
            AgentTriggerPolicy policy = AgentTriggerPolicy.builder()
                    .userPubId(userPubId)
                    .agentPubId(agentPubId)
                    .connectorCode(entry.connectorCode())
                    .triggerName(entry.name())
                    .effect(AccessEffect.ALLOW)
                    .source(SOURCE_SKILL)
                    .description("Auto-managed by skill bindings")
                    .build();
            agentTriggerPolicyRepository.save(policy);
        }
    }

    private void removePolicy(UUID agentPubId, PolicyDiffEntry entry) {
        if ("TOOL".equals(entry.policyType())) {
            AgentToolPolicy policy = agentToolPolicyRepository.findByCompositeKey(
                    agentPubId, entry.connectorCode(), null, entry.name(), AccessEffect.ALLOW.name());
            if (policy != null && SOURCE_SKILL.equals(policy.getSource())) {
                agentToolPolicyRepository.delete(policy);
            }
        } else {
            AgentTriggerPolicy policy = agentTriggerPolicyRepository.findByCompositeKey(
                    agentPubId, entry.connectorCode(), null, entry.name(), AccessEffect.ALLOW.name());
            if (policy != null && SOURCE_SKILL.equals(policy.getSource())) {
                agentTriggerPolicyRepository.delete(policy);
            }
        }
    }

    private Set<PolicyKey> buildDesiredPolicies(Set<UUID> skillPubIds) {
        if (skillPubIds.isEmpty()) {
            return Set.of();
        }

        List<SkillConnector> connectors = skillConnectorRepository.findBySkillPubIdIn(skillPubIds);
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

    private Set<PolicyKey> loadExistingSkillPolicies(UUID agentPubId) {
        Set<PolicyKey> existing = new HashSet<>();

        agentToolPolicyRepository.findByAgentPubIdAndSource(agentPubId, SOURCE_SKILL)
                .forEach(p -> existing.add(new PolicyKey("TOOL", p.getConnectorCode(), p.getToolName())));

        agentTriggerPolicyRepository.findByAgentPubIdAndSource(agentPubId, SOURCE_SKILL)
                .forEach(p -> existing.add(new PolicyKey("TRIGGER", p.getConnectorCode(), p.getTriggerName())));

        return existing;
    }

    private Set<PolicyKey> loadAllExistingAllowPolicies(UUID agentPubId) {
        Set<PolicyKey> existing = new HashSet<>();

        agentToolPolicyRepository.findByAgentPubId(agentPubId).stream()
                .filter(p -> p.getEffect() == AccessEffect.ALLOW)
                .forEach(p -> existing.add(new PolicyKey("TOOL", p.getConnectorCode(), p.getToolName())));

        agentTriggerPolicyRepository.findByAgentPubId(agentPubId).stream()
                .filter(p -> p.getEffect() == AccessEffect.ALLOW)
                .forEach(p -> existing.add(new PolicyKey("TRIGGER", p.getConnectorCode(), p.getTriggerName())));

        return existing;
    }

    private Set<UUID> getCurrentSkillPubIds(UUID agentPubId) {
        return agentSkillRepository.findByAgentPubId(agentPubId).stream()
                .map(AgentSkill::getSkillPubId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private record PolicyKey(String policyType, String connectorCode, String name) {
        PolicyDiffEntry toEntry() {
            return new PolicyDiffEntry(policyType, connectorCode, name);
        }
    }
}
