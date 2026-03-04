package ru.agimate.deviceapi.abac;

import java.util.UUID;

public record AccessDecision(
        boolean allowed,
        UUID matchedPolicyId,
        String reason
) {
    public static AccessDecision deny(String reason) {
        return new AccessDecision(false, null, reason);
    }

    public static AccessDecision deny(String reason, UUID matchedPolicyId) {
        return new AccessDecision(false, matchedPolicyId, reason);
    }

    public static AccessDecision allow(UUID policyId) {
        return new AccessDecision(true, policyId, "Allowed");
    }
}
