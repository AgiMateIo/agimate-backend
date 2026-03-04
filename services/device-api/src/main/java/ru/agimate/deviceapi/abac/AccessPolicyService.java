package ru.agimate.deviceapi.abac;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccessPolicyService {

    private final AccessPolicyRepository accessPolicyRepository;
    private final AccessEvaluatorService accessEvaluatorService;

    public List<AccessPolicy> getPoliciesByAgent(String agentName) {
        return accessPolicyRepository.findByAgentName(agentName);
    }

    public AccessPolicy getPolicyById(UUID id) {
        return accessPolicyRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Access policy not found"));
    }

    @Transactional
    public AccessPolicy createPolicy(String agentName, String connectorName, String connectorIdentity,
                                     String toolName, AccessEffect effect, Integer priority, String description) {
        validateConstraints(connectorName, connectorIdentity, toolName);

        AccessPolicy policy = AccessPolicy.builder()
                .agentName(agentName)
                .connectorName(connectorName)
                .connectorIdentity(connectorIdentity)
                .toolName(toolName)
                .effect(effect)
                .priority(priority)
                .description(description)
                .build();

        AccessPolicy saved = accessPolicyRepository.save(policy);
        accessEvaluatorService.invalidateByAgent(agentName);
        return saved;
    }

    @Transactional
    public AccessPolicy updatePolicy(UUID id, String connectorName, String connectorIdentity,
                                     String toolName, AccessEffect effect, Integer priority, String description) {
        AccessPolicy policy = getPolicyById(id);
        validateConstraints(connectorName, connectorIdentity, toolName);

        if (connectorName != null || policy.getConnectorName() != null) {
            policy.setConnectorName(connectorName);
        }
        policy.setConnectorIdentity(connectorIdentity);
        policy.setToolName(toolName);
        if (effect != null) {
            policy.setEffect(effect);
        }
        policy.setPriority(priority);
        if (description != null) {
            policy.setDescription(description);
        }

        AccessPolicy saved = accessPolicyRepository.save(policy);
        accessEvaluatorService.invalidateByAgent(policy.getAgentName());
        return saved;
    }

    @Transactional
    public void deletePolicy(UUID id) {
        AccessPolicy policy = getPolicyById(id);
        accessPolicyRepository.delete(policy);
        accessEvaluatorService.invalidateByAgent(policy.getAgentName());
    }

    private void validateConstraints(String connectorName, String connectorIdentity, String toolName) {
        if (connectorIdentity != null && connectorName == null) {
            throw new BadRequestStatusException("connector_identity requires connector_name to be set");
        }
        if (toolName != null && connectorName == null) {
            throw new BadRequestStatusException("tool_name requires connector_name to be set");
        }
    }
}
