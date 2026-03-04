package ru.agimate.deviceapi.abac;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
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

    public List<AgentToolPolicy> getPoliciesByAgent(UUID userPubId, UUID apiKeyPubId) {
        return agentToolPolicyRepository.findByUserPubIdAndApiKeyPubId(userPubId, apiKeyPubId);
    }

    public AgentToolPolicy getPolicyById(UUID userPubId, UUID id) {
        AgentToolPolicy policy = agentToolPolicyRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agent tool policy not found"));
        validateOwnership(policy, userPubId);
        return policy;
    }

    @Transactional
    public AgentToolPolicy createPolicy(UUID userPubId, UUID apiKeyPubId, String connectorCode, String connectorIdentity,
                                        String toolName, AccessEffect effect, Integer priority, String description) {
        validateConstraints(connectorCode, connectorIdentity, toolName);

        AgentToolPolicy policy = AgentToolPolicy.builder()
                .userPubId(userPubId)
                .apiKeyPubId(apiKeyPubId)
                .connectorCode(connectorCode)
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
    public AgentToolPolicy updatePolicy(UUID userPubId, UUID id, String connectorCode, String connectorIdentity,
                                        String toolName, AccessEffect effect, Integer priority, String description) {
        AgentToolPolicy policy = getPolicyById(userPubId, id);
        validateConstraints(connectorCode, connectorIdentity, toolName);

        if (connectorCode != null || policy.getConnectorCode() != null) {
            policy.setConnectorCode(connectorCode);
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
    public void deletePolicy(UUID userPubId, UUID id) {
        AgentToolPolicy policy = getPolicyById(userPubId, id);
        agentToolPolicyRepository.delete(policy);
        toolPolicyEvaluatorService.invalidateByAgent(policy.getApiKeyPubId());
    }

    private void validateOwnership(AgentToolPolicy policy, UUID userPubId) {
        if (!policy.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }
    }

    private void validateConstraints(String connectorCode, String connectorIdentity, String toolName) {
        if (connectorIdentity != null && connectorCode == null) {
            throw new BadRequestStatusException("connector_identity requires connector_code to be set");
        }
        if (toolName != null && connectorCode == null) {
            throw new BadRequestStatusException("tool_name requires connector_code to be set");
        }
    }
}
