package ru.agimate.deviceapi.abac;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccessEvaluatorService {

    private final AccessPolicyRepository accessPolicyRepository;

    private final Cache<AccessRequest, AccessDecision> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    public AccessDecision evaluate(AccessRequest request) {
        return cache.get(request, this::doEvaluate);
    }

    public void invalidateByAgent(String agentName) {
        cache.asMap().keySet().removeIf(key -> key.agentName().equals(agentName));
    }

    private AccessDecision doEvaluate(AccessRequest request) {
        List<AccessPolicy> matched = accessPolicyRepository.findMatchingPolicies(
                request.agentName(),
                request.connectorName(),
                request.connectorIdentity(),
                request.toolName()
        );

        if (matched.isEmpty()) {
            return AccessDecision.deny("No matching policy (default deny)");
        }

        int maxSpecificity = matched.stream()
                .mapToInt(this::getSpecificity)
                .max()
                .orElse(0);

        List<AccessPolicy> topGroup = matched.stream()
                .filter(p -> getSpecificity(p) == maxSpecificity)
                .toList();

        boolean hasDeny = topGroup.stream()
                .anyMatch(p -> p.getEffect() == AccessEffect.DENY);

        if (hasDeny) {
            UUID denyPolicyId = topGroup.stream()
                    .filter(p -> p.getEffect() == AccessEffect.DENY)
                    .findFirst()
                    .map(AccessPolicy::getId)
                    .orElse(null);
            return AccessDecision.deny("Denied by policy at specificity level " + maxSpecificity, denyPolicyId);
        }

        UUID allowPolicyId = topGroup.stream()
                .filter(p -> p.getEffect() == AccessEffect.ALLOW)
                .findFirst()
                .map(AccessPolicy::getId)
                .orElse(null);
        return AccessDecision.allow(allowPolicyId);
    }

    private int getSpecificity(AccessPolicy policy) {
        if (policy.getPriority() != null) {
            return policy.getPriority();
        }
        int spec = 0;
        if (policy.getConnectorName() != null) spec++;
        if (policy.getConnectorIdentity() != null) spec++;
        if (policy.getToolName() != null) spec++;
        return spec;
    }
}
