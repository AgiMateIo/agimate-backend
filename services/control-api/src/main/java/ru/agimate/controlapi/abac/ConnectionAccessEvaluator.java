package ru.agimate.controlapi.abac;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.AgentConnectionPolicy;
import ru.agimate.controlapi.database.enums.PolicyKind;
import ru.agimate.controlapi.database.repositories.AgentConnectionPolicyRepository;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The single ABAC evaluator on top of a binding (it replaces {@code ToolPolicyDbEvaluatorService} +
 * {@code TriggerPolicyDbEvaluatorService}). The model is <b>default-allow gated by the binding</b>:
 * <ol>
 *   <li>no active {@link AgentConnection} (agent↔connection) → <b>deny</b> (the connector is unavailable);</li>
 *   <li>a rule {@code (binding, kind, name)} matching the exact name → its effect;</li>
 *   <li>otherwise a binding-wide rule ({@code name IS NULL}) → its effect;</li>
 *   <li>otherwise → <b>allow</b> (the default).</li>
 * </ol>
 * The winning rule's {@code params_filter} is carried into {@link AccessDecision} and applied at the
 * call site (where the arguments and parameters are).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectionAccessEvaluator {

    private final AgentConnectionRepository agentConnectionRepository;
    private final AgentConnectionPolicyRepository policyRepository;

    private record CacheKey(UUID agentId, UUID connectionId, PolicyKind kind, String name) {}

    private final Cache<CacheKey, AccessDecision> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    public AccessDecision evaluate(UUID agentId, UUID connectionId, PolicyKind kind, String name) {
        var key = new CacheKey(agentId, connectionId, kind, name);
        return cache.get(key, k -> doEvaluate(agentId, connectionId, kind, name));
    }

    /** A convenience entry taking the connection_id as a string (= connections.id). An invalid UUID → deny. */
    public AccessDecision evaluate(UUID agentId, String connectionIdStr, PolicyKind kind, String name) {
        UUID connectionId;
        try {
            connectionId = UUID.fromString(connectionIdStr);
        } catch (IllegalArgumentException | NullPointerException e) {
            return AccessDecision.deny("Invalid connection id: " + connectionIdStr);
        }
        return evaluate(agentId, connectionId, kind, name);
    }

    public void invalidateByAgent(UUID agentId) {
        cache.asMap().keySet().removeIf(key -> key.agentId().equals(agentId));
    }

    public void invalidateByConnection(UUID connectionId) {
        cache.asMap().keySet().removeIf(key -> key.connectionId().equals(connectionId));
    }

    private AccessDecision doEvaluate(UUID agentId, UUID connectionId, PolicyKind kind, String name) {
        AgentConnection binding = agentConnectionRepository.findActiveBinding(agentId, connectionId).orElse(null);
        if (binding == null) {
            return AccessDecision.deny("Connector instance is not bound to the agent");
        }

        List<AgentConnectionPolicy> resolved = policyRepository.resolve(binding.getId(), kind, name);
        if (resolved.isEmpty()) {
            return AccessDecision.allow(null); // дефолт-allow при наличии binding
        }

        AgentConnectionPolicy winner = resolved.get(0); // точное имя приоритетнее wildcard (см. resolve)
        if (winner.getEffect() == AccessEffect.DENY) {
            return AccessDecision.deny(
                    "Denied by policy (" + (winner.isBindingWide() ? "binding-wide" : winner.getName()) + ")",
                    winner.getId());
        }
        return AccessDecision.allow(winner.getId(), winner.getParamsFilter());
    }
}
