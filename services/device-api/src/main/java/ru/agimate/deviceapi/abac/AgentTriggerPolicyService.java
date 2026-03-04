package ru.agimate.deviceapi.abac;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.database.entities.AgentTriggerPolicy;
import ru.agimate.deviceapi.database.repositories.AgentTriggerPolicyRepository;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentTriggerPolicyService {

    private final AgentTriggerPolicyRepository agentTriggerPolicyRepository;
    private final TriggerPolicyEvaluatorService triggerPolicyEvaluatorService;

    public List<AgentTriggerPolicy> getPoliciesByAgent(UUID apiKeyPubId) {
        return agentTriggerPolicyRepository.findByApiKeyPubId(apiKeyPubId);
    }

    public AgentTriggerPolicy getPolicyById(UUID id) {
        return agentTriggerPolicyRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agent trigger policy not found"));
    }

    @Transactional
    public AgentTriggerPolicy createPolicy(UUID apiKeyPubId, String connectorCode, String connectorIdentity,
                                           String triggerName, AccessEffect effect, Integer priority, String description) {
        validateConstraints(connectorCode, connectorIdentity, triggerName);

        AgentTriggerPolicy policy = AgentTriggerPolicy.builder()
                .apiKeyPubId(apiKeyPubId)
                .connectorCode(connectorCode)
                .connectorIdentity(connectorIdentity)
                .triggerName(triggerName)
                .effect(effect)
                .priority(priority)
                .description(description)
                .build();

        AgentTriggerPolicy saved = agentTriggerPolicyRepository.save(policy);
        triggerPolicyEvaluatorService.invalidateByAgent(apiKeyPubId);
        return saved;
    }

    @Transactional
    public AgentTriggerPolicy updatePolicy(UUID id, String connectorCode, String connectorIdentity,
                                           String triggerName, AccessEffect effect, Integer priority, String description) {
        AgentTriggerPolicy policy = getPolicyById(id);
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
        triggerPolicyEvaluatorService.invalidateByAgent(policy.getApiKeyPubId());
        return saved;
    }

    @Transactional
    public void deletePolicy(UUID id) {
        AgentTriggerPolicy policy = getPolicyById(id);
        agentTriggerPolicyRepository.delete(policy);
        triggerPolicyEvaluatorService.invalidateByAgent(policy.getApiKeyPubId());
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
