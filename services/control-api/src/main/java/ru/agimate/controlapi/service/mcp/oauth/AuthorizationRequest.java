package ru.agimate.controlapi.service.mcp.oauth;

/**
 * The query of an authorization request as the consent page received it. The page keeps no state
 * of its own, so every call to the backend carries the whole request and is validated anew.
 */
public record AuthorizationRequest(
        String responseType,
        String clientId,
        String redirectUri,
        String codeChallenge,
        String codeChallengeMethod,
        String state,
        String resource,
        String scope
) {}
