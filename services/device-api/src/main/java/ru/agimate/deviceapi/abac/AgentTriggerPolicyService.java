package ru.agimate.deviceapi.abac;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.AgentTriggerPolicy;
import ru.agimate.deviceapi.database.repositories.AgentRepository;
import ru.agimate.deviceapi.database.repositories.AgentTriggerPolicyRepository;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentTriggerPolicyService {

    private final AgentRepository agentRepository;
    private final AgentTriggerPolicyRepository agentTriggerPolicyRepository;
    private final TriggerPolicyEvaluatorService triggerPolicyEvaluatorService;

    public List<AgentTriggerPolicy> getPoliciesByAgent(UUID userPubId, UUID agentPubId) {
        return agentTriggerPolicyRepository.findByUserPubIdAndAgentPubId(userPubId, agentPubId);
    }

    public Page<AgentTriggerPolicy> getPoliciesByAgent(UUID userPubId, UUID agentPubId, int page, int size) {
        return agentTriggerPolicyRepository.findByUserPubIdAndAgentPubId(userPubId, agentPubId, PageRequest.of(page, size));
    }

    public AgentTriggerPolicy getPolicyById(UUID userPubId, UUID id) {
        AgentTriggerPolicy policy = agentTriggerPolicyRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agent trigger policy not found"));
        validateOwnership(policy, userPubId);
        return policy;
    }

    @Transactional
    public AgentTriggerPolicy createPolicy(UUID userPubId, UUID agentPubId, String connectorCode, String connectorIdentity,
                                           String triggerName, AccessEffect effect, Integer priority, String description) {
        validateConstraints(connectorCode, connectorIdentity, triggerName);

        AgentTriggerPolicy existing = agentTriggerPolicyRepository.findByCompositeKey(
                agentPubId, connectorCode, connectorIdentity, triggerName, effect.name());
        if (existing != null) {
            throw new BadRequestStatusException("Policy with the same parameters already exists");
        }

        AgentTriggerPolicy policy = AgentTriggerPolicy.builder()
                .userPubId(userPubId)
                .agentPubId(agentPubId)
                .connectorCode(connectorCode)
                .connectorIdentity(connectorIdentity)
                .triggerName(triggerName)
                .effect(effect)
                .priority(priority)
                .description(description)
                .build();

        AgentTriggerPolicy saved = agentTriggerPolicyRepository.save(policy);
        triggerPolicyEvaluatorService.invalidateByAgent(agentPubId);
        return saved;
    }

    @Transactional
    public AgentTriggerPolicy updatePolicy(UUID userPubId, UUID id, String connectorCode, String connectorIdentity,
                                           String triggerName, AccessEffect effect, Integer priority, String description) {
        AgentTriggerPolicy policy = getPolicyById(userPubId, id);
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
        triggerPolicyEvaluatorService.invalidateByAgent(policy.getAgentPubId());
        return saved;
    }

    @Transactional
    public void deletePolicy(UUID userPubId, UUID id) {
        AgentTriggerPolicy policy = getPolicyById(userPubId, id);
        agentTriggerPolicyRepository.delete(policy);
        triggerPolicyEvaluatorService.invalidateByAgent(policy.getAgentPubId());
    }

    public List<Agent> findAllowedAgents(UUID userPubId, String connectorCode,
                                         String connectorIdentity, String triggerName) {
        return agentRepository.findAllowedAgents(userPubId, connectorCode, connectorIdentity, triggerName);
    }

    public List<Agent> findAllowedAgentsForTeamId(UUID userPubId, UUID teamPubId, String connectorCode,
                                         String connectorIdentity, String triggerName) {
        return agentRepository.findAllowedAgentsForTeamId(userPubId, teamPubId, connectorCode, connectorIdentity, triggerName);
    }

    private void validateOwnership(AgentTriggerPolicy policy, UUID userPubId) {
        if (!policy.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }
    }

    private void validateConstraints(String connectorCode, String connectorIdentity, String triggerName) {
        if (connectorIdentity != null && connectorCode == null) {
            throw new BadRequestStatusException("connector_identity requires connector_code to be set");
        }
        if (triggerName != null && connectorCode == null) {
            throw new BadRequestStatusException("trigger_name requires connector_code to be set");
        }
    }
}
