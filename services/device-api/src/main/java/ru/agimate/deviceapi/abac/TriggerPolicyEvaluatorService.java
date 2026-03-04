package ru.agimate.deviceapi.abac;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.database.entities.AgentTriggerPolicy;
import ru.agimate.deviceapi.database.repositories.AgentTriggerPolicyRepository;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TriggerPolicyEvaluatorService {

    private final AgentTriggerPolicyRepository agentTriggerPolicyRepository;

    private record CacheKey(UUID apiKeyPubId, String connectorCode, String connectorIdentity, String triggerName) {}

    private final Cache<CacheKey, AccessDecision> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    public AccessDecision evaluate(UUID apiKeyPubId, String connectorCode, String connectorIdentity, String triggerName) {
        var key = new CacheKey(apiKeyPubId, connectorCode, connectorIdentity, triggerName);
        return cache.get(key, k -> doEvaluate(apiKeyPubId, connectorCode, connectorIdentity, triggerName));
    }

    public void invalidateByAgent(UUID apiKeyPubId) {
        cache.asMap().keySet().removeIf(key -> key.apiKeyPubId().equals(apiKeyPubId));
    }

    private AccessDecision doEvaluate(UUID apiKeyPubId, String connectorCode, String connectorIdentity, String triggerName) {
        List<AgentTriggerPolicy> matched = agentTriggerPolicyRepository.findMatchingPolicies(
                apiKeyPubId, connectorCode, connectorIdentity, triggerName
        );

        if (matched.isEmpty()) {
            return AccessDecision.deny("No matching policy (default deny)");
        }

        int maxSpecificity = matched.stream()
                .mapToInt(this::getSpecificity)
                .max()
                .orElse(0);

        List<AgentTriggerPolicy> topGroup = matched.stream()
                .filter(p -> getSpecificity(p) == maxSpecificity)
                .toList();

        boolean hasDeny = topGroup.stream()
                .anyMatch(p -> p.getEffect() == AccessEffect.DENY);

        if (hasDeny) {
            UUID denyPolicyId = topGroup.stream()
                    .filter(p -> p.getEffect() == AccessEffect.DENY)
                    .findFirst()
                    .map(AgentTriggerPolicy::getId)
                    .orElse(null);
            return AccessDecision.deny("Denied by policy at specificity level " + maxSpecificity, denyPolicyId);
        }

        UUID allowPolicyId = topGroup.stream()
                .filter(p -> p.getEffect() == AccessEffect.ALLOW)
                .findFirst()
                .map(AgentTriggerPolicy::getId)
                .orElse(null);
        return AccessDecision.allow(allowPolicyId);
    }

    private int getSpecificity(AgentTriggerPolicy policy) {
        if (policy.getPriority() != null) {
            return policy.getPriority();
        }
        int spec = 0;
        if (policy.getConnectorCode() != null) spec++;
        if (policy.getConnectorIdentity() != null) spec++;
        if (policy.getTriggerName() != null) spec++;
        return spec;
    }
}
