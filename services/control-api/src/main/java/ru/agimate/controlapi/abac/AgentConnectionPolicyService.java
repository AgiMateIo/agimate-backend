package ru.agimate.controlapi.abac;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.AgentConnectionPolicy;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.enums.PolicyKind;
import ru.agimate.controlapi.database.repositories.AgentConnectionPolicyRepository;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD of the access refinement rules ({@code agent_connection_policies}) on top of a binding.
 * Ownership is checked through {@code binding → connection.userId}. Any change invalidates the
 * {@link ConnectionAccessEvaluator} cache for the binding's agent.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentConnectionPolicyService {

    private final AgentConnectionPolicyRepository policyRepository;
    private final AgentConnectionRepository agentConnectionRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectionAccessEvaluator accessEvaluator;

    public List<AgentConnectionPolicy> getPolicies(UUID userId, UUID agentConnectionId) {
        ownedBinding(userId, agentConnectionId);
        return policyRepository.findActiveByAgentConnectionId(agentConnectionId);
    }

    public AgentConnectionPolicy getPolicyById(UUID userId, UUID id) {
        AgentConnectionPolicy policy = policyRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Policy not found"));
        ownedBinding(userId, policy.getAgentConnectionId());
        return policy;
    }

    @Transactional
    public AgentConnectionPolicy create(UUID userId, UUID agentConnectionId, PolicyKind kind, String name,
                                        AccessEffect effect, Map<String, Object> paramsFilter, String description) {
        AgentConnection binding = ownedBinding(userId, agentConnectionId);
        if (policyRepository.findActive(agentConnectionId, kind, name).isPresent()) {
            throw new ConflictStatusException("Policy already exists for this " + kind + "/" + name);
        }
        if (effect == null || kind == null) {
            throw new BadRequestStatusException("kind and effect are required");
        }
        AgentConnectionPolicy saved = policyRepository.save(AgentConnectionPolicy.builder()
                .agentConnectionId(agentConnectionId)
                .kind(kind)
                .name(name)
                .effect(effect)
                .paramsFilter(paramsFilter)
                .description(description)
                .build());
        accessEvaluator.invalidateByAgent(binding.getAgentId());
        return saved;
    }

    @Transactional
    public AgentConnectionPolicy update(UUID userId, UUID agentConnectionId, UUID id, AccessEffect effect,
                                        Map<String, Object> paramsFilter, String description) {
        AgentConnectionPolicy policy = getPolicyById(userId, id);
        requireInBinding(policy, agentConnectionId);
        if (effect != null) {
            policy.setEffect(effect);
        }
        policy.setParamsFilter(paramsFilter);
        if (description != null) {
            policy.setDescription(description);
        }
        AgentConnectionPolicy saved = policyRepository.save(policy);
        invalidate(policy.getAgentConnectionId());
        return saved;
    }

    @Transactional
    public void delete(UUID userId, UUID agentConnectionId, UUID id) {
        AgentConnectionPolicy policy = getPolicyById(userId, id);
        requireInBinding(policy, agentConnectionId);
        policyRepository.softDelete(id, LocalDateTime.now());
        invalidate(policy.getAgentConnectionId());
    }

    /** The rule must belong to the binding from the path — otherwise the path is misleading (a mismatch). */
    private void requireInBinding(AgentConnectionPolicy policy, UUID agentConnectionId) {
        if (!policy.getAgentConnectionId().equals(agentConnectionId)) {
            throw new NotFoundStatusException("Policy not found");
        }
    }

    private AgentConnection ownedBinding(UUID userId, UUID agentConnectionId) {
        AgentConnection binding = agentConnectionRepository.findById(agentConnectionId)
                .filter(AgentConnection::isActive)
                .orElseThrow(() -> new NotFoundStatusException("Binding not found"));
        Connection connection = connectionRepository.findByIdNotDeleted(binding.getConnectionId())
                .orElseThrow(() -> new NotFoundStatusException("Connection not found"));
        if (!connection.getUserId().equals(userId)) {
            throw new NotFoundStatusException("Binding not found");
        }
        return binding;
    }

    private void invalidate(UUID agentConnectionId) {
        agentConnectionRepository.findById(agentConnectionId)
                .ifPresent(b -> accessEvaluator.invalidateByAgent(b.getAgentId()));
    }
}
