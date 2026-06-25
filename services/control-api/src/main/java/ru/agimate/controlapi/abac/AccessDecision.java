package ru.agimate.controlapi.abac;

import java.util.Map;
import java.util.UUID;

/**
 * Решение доступа. {@link #paramsFilter} переносит фильтр сработавшего правила
 * ({@code agent_connection_policies.params_filter}) — он применяется на месте вызова, где доступны
 * аргументы тула / параметры триггера (в самом эвалуаторе их нет).
 */
public record AccessDecision(
        AccessEffect accessEffect,
        UUID matchedPolicyId,
        String reason,
        Map<String, Object> paramsFilter
) {
    public boolean allowed() {
        return accessEffect == AccessEffect.ALLOW;
    }

    public static AccessDecision deny(String reason) {
        return new AccessDecision(AccessEffect.DENY, null, reason, null);
    }

    public static AccessDecision deny(String reason, UUID matchedPolicyId) {
        return new AccessDecision(AccessEffect.DENY, matchedPolicyId, reason, null);
    }

    public static AccessDecision allow(UUID policyId) {
        return new AccessDecision(AccessEffect.ALLOW, policyId, null, null);
    }

    public static AccessDecision allow(UUID policyId, Map<String, Object> paramsFilter) {
        return new AccessDecision(AccessEffect.ALLOW, policyId, null, paramsFilter);
    }
}
