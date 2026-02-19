package ru.agimate.deviceapi.security;

import java.security.Principal;
import java.util.UUID;

public record AppPrincipal(
        String name,
        UUID appPubId,
        UUID userPubId
) implements Principal {

    @Override
    public String getName() {
        return name;
    }
}
