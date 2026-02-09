package ru.agimate.connectorsapi.service.dto;

/**
 * Result of API key introspection via gRPC call to user-api.
 */
public record ApiKeyIntrospectResult(
        String keyPubId,
        String userPubId
) {}
