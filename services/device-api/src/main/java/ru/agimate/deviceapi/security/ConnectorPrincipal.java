package ru.agimate.deviceapi.security;

import java.security.Principal;
import java.util.UUID;

public record ConnectorPrincipal(
        String name,
        UUID connectorPubId,
        UUID userPubId
) implements Principal {

    @Override
    public String getName() {
        return name;
    }
}
