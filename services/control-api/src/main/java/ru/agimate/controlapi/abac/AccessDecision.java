package ru.agimate.controlapi.abac;

import java.util.UUID;

public record AccessDecision(
        AccessEffect accessEffect,
        UUID matchedPolicyId,
        String reason
) {
    public boolean allowed() {
        return accessEffect == AccessEffect.ALLOW;
    }

    public static AccessDecision deny(String reason) {
        return new AccessDecision(AccessEffect.DENY, null, reason);
    }

    public static AccessDecision deny(String reason, UUID matchedPolicyId) {
        return new AccessDecision(AccessEffect.DENY, matchedPolicyId, reason);
    }

    public static AccessDecision allow(UUID policyId) {
        return new AccessDecision(AccessEffect.ALLOW, policyId, null);
    }
}
