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
 * Единый ABAC-эвалуатор поверх binding (заменяет {@code ToolPolicyDbEvaluatorService} +
 * {@code TriggerPolicyDbEvaluatorService}). Модель — <b>дефолт-allow с гейтом по binding</b>:
 * <ol>
 *   <li>нет активного {@link AgentConnection} (agent↔connection) → <b>deny</b> (коннектор недоступен);</li>
 *   <li>правило {@code (binding, kind, name)} по точному имени → его effect;</li>
 *   <li>иначе binding-wide правило ({@code name IS NULL}) → его effect;</li>
 *   <li>иначе → <b>allow</b> (дефолт).</li>
 * </ol>
 * {@code params_filter} победившего правила переносится в {@link AccessDecision} и применяется на
 * месте вызова (там есть аргументы/параметры).
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

    /** Удобный вход с connection_id-строкой (= connections.id). Невалидный UUID → deny. */
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
