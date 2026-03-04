package ru.agimate.deviceapi.abac;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.database.repositories.AgentToolPolicyRepository;
import ru.agimate.deviceapi.database.projections.PolicyResolutionResult;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ToolPolicyDbEvaluatorService {

    private final AgentToolPolicyRepository agentToolPolicyRepository;

    private record CacheKey(UUID apiKeyPubId, String connectorCode, String connectorIdentity, String toolName) {}

    private final Cache<CacheKey, AccessDecision> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    public AccessDecision evaluate(UUID apiKeyPubId, String connectorCode, String connectorIdentity, String toolName) {
        var key = new CacheKey(apiKeyPubId, connectorCode, connectorIdentity, toolName);
        return cache.get(key, k -> doEvaluate(apiKeyPubId, connectorCode, connectorIdentity, toolName));
    }

    public void invalidateByAgent(UUID apiKeyPubId) {
        cache.asMap().keySet().removeIf(key -> key.apiKeyPubId().equals(apiKeyPubId));
    }

    private AccessDecision doEvaluate(UUID apiKeyPubId, String connectorCode, String connectorIdentity, String toolName) {
        PolicyResolutionResult result = agentToolPolicyRepository.resolveAccess(
                apiKeyPubId, connectorCode, connectorIdentity, toolName
        );

        if (result == null) {
            return AccessDecision.deny("No matching policy (default deny)");
        }

        if ("DENY".equals(result.getEffect())) {
            return AccessDecision.deny("Denied by policy at specificity level " + result.getSpecificity(), result.getId());
        }

        return AccessDecision.allow(result.getId());
    }
}
