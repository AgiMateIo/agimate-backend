package ru.agimate.deviceapi.security;

import java.security.Principal;
import java.util.UUID;

public record AgentPrincipal(
        String name,
        UUID agentId,
        UUID userId
) implements Principal {

    @Override
    public String getName() {
        return name;
    }
}
