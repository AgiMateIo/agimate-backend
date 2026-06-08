package ru.agimate.controlapi.abac;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentTriggerPolicy;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentTriggerPolicyRepository;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentTriggerPolicyService {

    private final AgentRepository agentRepository;
    private final AgentTriggerPolicyRepository agentTriggerPolicyRepository;
    private final TriggerPolicyDbEvaluatorService triggerPolicyEvaluatorService;

    public List<AgentTriggerPolicy> getPoliciesByAgent(UUID userId, UUID agentId) {
        return agentTriggerPolicyRepository.findByUserIdAndAgentId(userId, agentId);
    }

    public Page<AgentTriggerPolicy> getPoliciesByAgent(UUID userId, UUID agentId, int page, int size) {
        return agentTriggerPolicyRepository.findByUserIdAndAgentId(userId, agentId, PageRequest.of(page, size));
    }

    public AgentTriggerPolicy getPolicyById(UUID userId, UUID id) {
        AgentTriggerPolicy policy = agentTriggerPolicyRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agent trigger policy not found"));
        validateOwnership(policy, userId);
        return policy;
    }

    @Transactional
    public AgentTriggerPolicy createPolicy(UUID userId, UUID agentId, String connectorCode, String connectorIdentity,
                                           String triggerName, AccessEffect effect, Integer priority, String description) {
        validateConstraints(connectorCode, connectorIdentity, triggerName);

        AgentTriggerPolicy existing = agentTriggerPolicyRepository.findByCompositeKey(
                agentId, connectorCode, connectorIdentity, triggerName, effect.name());
        if (existing != null) {
            throw new BadRequestStatusException("Policy with the same parameters already exists");
        }

        AgentTriggerPolicy policy = AgentTriggerPolicy.builder()
                .userId(userId)
                .agentId(agentId)
                .connectorCode(connectorCode)
                .connectorIdentity(connectorIdentity)
                .triggerName(triggerName)
                .effect(effect)
                .priority(priority)
                .description(description)
                .build();

        AgentTriggerPolicy saved = agentTriggerPolicyRepository.save(policy);
        triggerPolicyEvaluatorService.invalidateByAgent(agentId);
        return saved;
    }

    @Transactional
    public AgentTriggerPolicy updatePolicy(UUID userId, UUID id, String connectorCode, String connectorIdentity,
                                           String triggerName, AccessEffect effect, Integer priority, String description) {
        AgentTriggerPolicy policy = getPolicyById(userId, id);
        validateConstraints(connectorCode, connectorIdentity, triggerName);

        if (connectorCode != null || policy.getConnectorCode() != null) {
            policy.setConnectorCode(connectorCode);
        }
        policy.setConnectorIdentity(connectorIdentity);
        policy.setTriggerName(triggerName);
        if (effect != null) {
            policy.setEffect(effect);
        }
        policy.setPriority(priority);
        if (description != null) {
            policy.setDescription(description);
        }

        AgentTriggerPolicy saved = agentTriggerPolicyRepository.save(policy);
        triggerPolicyEvaluatorService.invalidateByAgent(policy.getAgentId());
        return saved;
    }

    @Transactional
    public void deletePolicy(UUID userId, UUID id) {
        AgentTriggerPolicy policy = getPolicyById(userId, id);
        agentTriggerPolicyRepository.delete(policy);
        triggerPolicyEvaluatorService.invalidateByAgent(policy.getAgentId());
    }

    public List<Agent> findAllowedAgents(UUID userId, String connectorCode,
                                         String connectorIdentity, String triggerName) {
        return agentRepository.findAllowedAgents(userId, connectorCode, connectorIdentity, triggerName);
    }

    public List<Agent> findAllowedAgentsForTeamId(UUID userId, UUID teamId, String connectorCode,
                                         String connectorIdentity, String triggerName) {
        return agentRepository.findAllowedAgentsForTeamId(userId, teamId, connectorCode, connectorIdentity, triggerName);
    }

    private void validateOwnership(AgentTriggerPolicy policy, UUID userId) {
        PolicyValidationUtils.validateOwnership(policy.getUserId(), userId);
    }

    private void validateConstraints(String connectorCode, String connectorIdentity, String triggerName) {
        PolicyValidationUtils.validateConstraints(connectorCode, connectorIdentity, triggerName, "trigger_name");
    }
}
