package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

/**
 * A successful registration, with the two facts that do not belong to {@link ClientIdentity} but must
 * travel with it into the flow keys: the scope the client was registered for and the secret's expiry
 * in epoch seconds. Both may be unknown ({@code null}).
 */
public record RegisteredClient(ClientIdentity identity, String registeredScope, Long secretExpiresAt) {
}
