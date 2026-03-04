package ru.agimate.deviceapi.abac;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.database.entities.AgentToolPolicy;
import ru.agimate.deviceapi.database.repositories.AgentToolPolicyRepository;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentToolPolicyService {

    private final AgentToolPolicyRepository agentToolPolicyRepository;
    private final ToolPolicyEvaluatorService toolPolicyEvaluatorService;

    public List<AgentToolPolicy> getPoliciesByAgent(UUID apiKeyPubId) {
        return agentToolPolicyRepository.findByApiKeyPubId(apiKeyPubId);
    }

    public AgentToolPolicy getPolicyById(UUID id) {
        return agentToolPolicyRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agent tool policy not found"));
    }

    @Transactional
    public AgentToolPolicy createPolicy(UUID apiKeyPubId, String connectorName, String connectorIdentity,
                                        String toolName, AccessEffect effect, Integer priority, String description) {
        validateConstraints(connectorName, connectorIdentity, toolName);

        AgentToolPolicy policy = AgentToolPolicy.builder()
                .apiKeyPubId(apiKeyPubId)
                .connectorName(connectorName)
                .connectorIdentity(connectorIdentity)
                .toolName(toolName)
                .effect(effect)
                .priority(priority)
                .description(description)
                .build();

        AgentToolPolicy saved = agentToolPolicyRepository.save(policy);
        toolPolicyEvaluatorService.invalidateByAgent(apiKeyPubId);
        return saved;
    }

    @Transactional
    public AgentToolPolicy updatePolicy(UUID id, String connectorName, String connectorIdentity,
                                        String toolName, AccessEffect effect, Integer priority, String description) {
        AgentToolPolicy policy = getPolicyById(id);
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

        AgentToolPolicy saved = agentToolPolicyRepository.save(policy);
        toolPolicyEvaluatorService.invalidateByAgent(policy.getApiKeyPubId());
        return saved;
    }

    @Transactional
    public void deletePolicy(UUID id) {
        AgentToolPolicy policy = getPolicyById(id);
        agentToolPolicyRepository.delete(policy);
        toolPolicyEvaluatorService.invalidateByAgent(policy.getApiKeyPubId());
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
