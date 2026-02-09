package ru.agimate.common.security.apikey;

/**
 * Result of API key introspection via gRPC call to user-api.
 */
public record ApiKeyIntrospectResult(
        String keyPubId,
        String userPubId
) {}
