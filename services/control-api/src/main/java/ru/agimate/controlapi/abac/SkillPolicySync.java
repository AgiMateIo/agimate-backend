package ru.agimate.controlapi.abac;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.AgentConnectionPolicy;
import ru.agimate.controlapi.database.enums.PolicyKind;
import ru.agimate.controlapi.database.model.ConnectorRequirement;
import ru.agimate.controlapi.database.model.ConnectorRequirement.Rule;
import ru.agimate.controlapi.database.model.ConnectorRequirement.Rules;
import ru.agimate.controlapi.database.repositories.AgentConnectionPolicyRepository;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The access rules a skill declares, materialised as {@code agent_connection_policies} rows on the
 * agent's binding to the instance the skill means. Advisory, not enforced: the rows are written when
 * the user binds the skill (or accepts its new version) and carry {@code source = skill:<id>}; a rule
 * the user edits sheds that source and is theirs from then on, and a rule of any other origin on the
 * same {@code (kind, name)} is a conflict reported back, never overwritten. The evaluator and the
 * listing do not know skills exist — this only adds rows to the table they already read.
 * See {@code docs/decisions/skill-connector-requirements.md}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SkillPolicySync {

    static final String SOURCE_PREFIX = "skill:";

    private final AgentConnectionPolicyRepository policyRepository;
    private final AgentConnectionRepository agentConnectionRepository;
    private final ConnectionAccessEvaluator accessEvaluator;

    /** One row the declaration asks for; {@code name == null} is the binding-wide rule. */
    public record DesiredPolicy(PolicyKind kind, String name, AccessEffect effect, Map<String, Object> paramsFilter) {
        String label() {
            return kind + "/" + (name == null ? "*" : name);
        }
    }

    public static String source(UUID skillId) {
        return SOURCE_PREFIX + skillId;
    }

    /**
     * The rows a requirement translates to: an allow-list is a binding-wide DENY plus an ALLOW per
     * entry (its params as the filter); a deny-list is a DENY per name on top of default-allow.
     */
    public static List<DesiredPolicy> desired(ConnectorRequirement requirement) {
        List<DesiredPolicy> result = new ArrayList<>();
        desired(requirement.tools(), PolicyKind.TOOL, result);
        desired(requirement.triggers(), PolicyKind.TRIGGER, result);
        return result;
    }

    private static void desired(Rules rules, PolicyKind kind, List<DesiredPolicy> into) {
        if (rules == null) {
            return;
        }
        if (rules.hasAllowList()) {
            into.add(new DesiredPolicy(kind, null, AccessEffect.DENY, null));
            for (Rule rule : rules.allow()) {
                into.add(new DesiredPolicy(kind, rule.name(), AccessEffect.ALLOW, rule.params()));
            }
        } else {
            for (String name : rules.deny()) {
                into.add(new DesiredPolicy(kind, name, AccessEffect.DENY, null));
            }
        }
    }

    /**
     * Which of the requirement's rows collide with rows of another origin on the agent's binding to
     * {@code connectionId} — the read-only half of {@link #apply}, for listings. Empty when there is no
     * binding: without one there is nothing to collide with, and the status says «not bound» anyway.
     */
    public List<String> conflicts(UUID agentId, UUID connectionId, UUID skillId, ConnectorRequirement requirement) {
        return binding(agentId, connectionId)
                .map(binding -> diff(binding, skillId, desired(requirement)).conflicts())
                .orElse(List.of());
    }

    /**
     * Writes the requirement's rows onto the agent's binding to {@code connectionId} and returns the
     * conflicts. Rows of this skill that the declaration no longer asks for are dropped, so a re-run
     * after the author changed the rules is a reset to what the author wants. No binding — nothing is
     * written: the skill is unsatisfied, and the wizard binds the connection before the skill.
     */
    @Transactional
    public List<String> apply(UUID agentId, UUID connectionId, UUID skillId, ConnectorRequirement requirement) {
        Optional<AgentConnection> binding = binding(agentId, connectionId);
        if (binding.isEmpty()) {
            return List.of();
        }
        Diff diff = diff(binding.get(), skillId, desired(requirement));
        String source = source(skillId);
        LocalDateTime now = LocalDateTime.now();
        for (DesiredPolicy row : diff.toCreate()) {
            policyRepository.save(AgentConnectionPolicy.builder()
                    .agentConnectionId(binding.get().getId())
                    .kind(row.kind())
                    .name(row.name())
                    .effect(row.effect())
                    .paramsFilter(row.paramsFilter())
                    .source(source)
                    .build());
        }
        diff.toUpdate().forEach((policy, row) -> {
            policy.setEffect(row.effect());
            policy.setParamsFilter(row.paramsFilter());
            policyRepository.save(policy);
        });
        for (AgentConnectionPolicy stale : diff.toDelete()) {
            stale.setDeletedAt(now);
            policyRepository.save(stale);
        }
        if (!diff.isEmpty()) {
            accessEvaluator.invalidateByAgent(agentId);
        }
        if (!diff.conflicts().isEmpty()) {
            log.info("Skill {} policies on binding {}: {} rule(s) left alone as foreign: {}",
                    skillId, binding.get().getId(), diff.conflicts().size(), diff.conflicts());
        }
        return diff.conflicts();
    }

    /** Unbinding the skill from the agent takes its rows on that agent with it. */
    @Transactional
    public void remove(UUID agentId, UUID skillId) {
        retire(policyRepository.findActiveBySourceAndAgent(source(skillId), agentId));
        accessEvaluator.invalidateByAgent(agentId);
    }

    /** Deleting the skill takes its rows everywhere — its bindings go the same way. */
    @Transactional
    public void removeEverywhere(UUID skillId) {
        List<AgentConnectionPolicy> rows = policyRepository.findActiveBySource(source(skillId));
        retire(rows);
        Set<UUID> bindingIds = new HashSet<>();
        rows.forEach(row -> bindingIds.add(row.getAgentConnectionId()));
        agentConnectionRepository.findAllById(bindingIds)
                .forEach(binding -> accessEvaluator.invalidateByAgent(binding.getAgentId()));
    }

    private void retire(List<AgentConnectionPolicy> rows) {
        LocalDateTime now = LocalDateTime.now();
        rows.forEach(row -> row.setDeletedAt(now));
        policyRepository.saveAll(rows);
    }

    private Optional<AgentConnection> binding(UUID agentId, UUID connectionId) {
        return agentConnectionRepository.findActiveBinding(agentId, connectionId);
    }

    private record PolicyKey(PolicyKind kind, String name) {
    }

    private record Diff(List<DesiredPolicy> toCreate, Map<AgentConnectionPolicy, DesiredPolicy> toUpdate,
                        List<AgentConnectionPolicy> toDelete, List<String> conflicts) {
        boolean isEmpty() {
            return toCreate.isEmpty() && toUpdate.isEmpty() && toDelete.isEmpty();
        }
    }

    /**
     * Desired against existing, by {@code (kind, name)}: absent — create; ours — update; anyone
     * else's — conflict, even when it says the same thing, so that the dependence of one skill on
     * another's row is visible at bind time rather than when that other skill is unbound.
     */
    private Diff diff(AgentConnection binding, UUID skillId, List<DesiredPolicy> desired) {
        String source = source(skillId);
        Map<PolicyKey, AgentConnectionPolicy> existing = new HashMap<>();
        for (AgentConnectionPolicy policy : policyRepository.findActiveByAgentConnectionId(binding.getId())) {
            existing.put(new PolicyKey(policy.getKind(), policy.getName()), policy);
        }
        List<DesiredPolicy> toCreate = new ArrayList<>();
        Map<AgentConnectionPolicy, DesiredPolicy> toUpdate = new HashMap<>();
        List<String> conflicts = new ArrayList<>();
        Set<AgentConnectionPolicy> kept = new HashSet<>();
        for (DesiredPolicy row : desired) {
            AgentConnectionPolicy current = existing.get(new PolicyKey(row.kind(), row.name()));
            if (current == null) {
                toCreate.add(row);
            } else if (source.equals(current.getSource())) {
                toUpdate.put(current, row);
                kept.add(current);
            } else {
                conflicts.add(row.label());
            }
        }
        List<AgentConnectionPolicy> toDelete = existing.values().stream()
                .filter(policy -> source.equals(policy.getSource()) && !kept.contains(policy))
                .toList();
        return new Diff(toCreate, toUpdate, toDelete, conflicts);
    }
}
