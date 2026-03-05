package ru.agimate.deviceapi.abac;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.database.entities.AgentToolPolicy;
import ru.agimate.deviceapi.database.repositories.AgentToolPolicyRepository;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ToolPolicyEvaluatorService {

    private final AgentToolPolicyRepository agentToolPolicyRepository;

    private record CacheKey(UUID agentPubId, String connectorCode, String connectorIdentity, String toolName) {}

    private final Cache<CacheKey, AccessDecision> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    public AccessDecision evaluate(UUID agentPubId, String connectorCode, String connectorIdentity, String toolName) {
        var key = new CacheKey(agentPubId, connectorCode, connectorIdentity, toolName);
        return cache.get(key, k -> doEvaluate(agentPubId, connectorCode, connectorIdentity, toolName));
    }

    public void invalidateByAgent(UUID agentPubId) {
        cache.asMap().keySet().removeIf(key -> key.agentPubId().equals(agentPubId));
    }

    private AccessDecision doEvaluate(UUID agentPubId, String connectorCode, String connectorIdentity, String toolName) {
        List<AgentToolPolicy> matched = agentToolPolicyRepository.findMatchingPolicies(
                agentPubId, connectorCode, connectorIdentity, toolName
        );

        if (matched.isEmpty()) {
            return AccessDecision.deny("No matching policy (default deny)");
        }

        int maxSpecificity = matched.stream()
                .mapToInt(this::getSpecificity)
                .max()
                .orElse(0);

        List<AgentToolPolicy> topGroup = matched.stream()
                .filter(p -> getSpecificity(p) == maxSpecificity)
                .toList();

        boolean hasDeny = topGroup.stream()
                .anyMatch(p -> p.getEffect() == AccessEffect.DENY);

        if (hasDeny) {
            UUID denyPolicyId = topGroup.stream()
                    .filter(p -> p.getEffect() == AccessEffect.DENY)
                    .findFirst()
                    .map(AgentToolPolicy::getId)
                    .orElse(null);
            return AccessDecision.deny("Denied by policy at specificity level " + maxSpecificity, denyPolicyId);
        }

        UUID allowPolicyId = topGroup.stream()
                .filter(p -> p.getEffect() == AccessEffect.ALLOW)
                .findFirst()
                .map(AgentToolPolicy::getId)
                .orElse(null);
        return AccessDecision.allow(allowPolicyId);
    }

    private int getSpecificity(AgentToolPolicy policy) {
        if (policy.getPriority() != null) {
            return policy.getPriority();
        }
        int spec = 0;
        if (policy.getConnectorCode() != null) spec++;
        if (policy.getConnectorIdentity() != null) spec++;
        if (policy.getToolName() != null) spec++;
        return spec;
    }
}
