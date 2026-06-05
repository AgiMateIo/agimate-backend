package ru.agimate.deviceapi.abac;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
    private final ToolPolicyDbEvaluatorService toolPolicyEvaluatorService;

    public List<AgentToolPolicy> getPoliciesByAgent(UUID userId, UUID agentId) {
        return agentToolPolicyRepository.findByUserIdAndAgentId(userId, agentId);
    }

    public Page<AgentToolPolicy> getPoliciesByAgent(UUID userId, UUID agentId, int page, int size) {
        return agentToolPolicyRepository.findByUserIdAndAgentId(userId, agentId, PageRequest.of(page, size));
    }

    public AgentToolPolicy getPolicyById(UUID userId, UUID id) {
        AgentToolPolicy policy = agentToolPolicyRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agent tool policy not found"));
        validateOwnership(policy, userId);
        return policy;
    }

    @Transactional
    public AgentToolPolicy createPolicy(UUID userId, UUID agentId, String connectorCode, String connectorIdentity,
                                        String toolName, AccessEffect effect, Integer priority, String description) {
        validateConstraints(connectorCode, connectorIdentity, toolName);

        AgentToolPolicy existing = agentToolPolicyRepository.findByCompositeKey(
                agentId, connectorCode, connectorIdentity, toolName, effect.name());
        if (existing != null) {
            throw new BadRequestStatusException("Policy with the same parameters already exists");
        }

        AgentToolPolicy policy = AgentToolPolicy.builder()
                .userId(userId)
                .agentId(agentId)
                .connectorCode(connectorCode)
                .connectorIdentity(connectorIdentity)
                .toolName(toolName)
                .effect(effect)
                .priority(priority)
                .description(description)
                .build();

        AgentToolPolicy saved = agentToolPolicyRepository.save(policy);
        toolPolicyEvaluatorService.invalidateByAgent(agentId);
        return saved;
    }

    @Transactional
    public AgentToolPolicy updatePolicy(UUID userId, UUID id, String connectorCode, String connectorIdentity,
                                        String toolName, AccessEffect effect, Integer priority, String description) {
        AgentToolPolicy policy = getPolicyById(userId, id);
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
        toolPolicyEvaluatorService.invalidateByAgent(policy.getAgentId());
        return saved;
    }

    @Transactional
    public void deletePolicy(UUID userId, UUID id) {
        AgentToolPolicy policy = getPolicyById(userId, id);
        agentToolPolicyRepository.delete(policy);
        toolPolicyEvaluatorService.invalidateByAgent(policy.getAgentId());
    }

    private void validateOwnership(AgentToolPolicy policy, UUID userId) {
        PolicyValidationUtils.validateOwnership(policy.getUserId(), userId);
    }

    private void validateConstraints(String connectorCode, String connectorIdentity, String toolName) {
        PolicyValidationUtils.validateConstraints(connectorCode, connectorIdentity, toolName, "tool_name");
    }
}
