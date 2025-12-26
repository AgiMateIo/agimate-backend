package ru.agimate.mobileapi.security;

import java.security.Principal;
import java.util.UUID;

public record DevicePrincipal(
        String name,
        UUID connectionKeyPubId,
        UUID userPubId
) implements Principal {

    @Override
    public String getName() {
        return name;
    }
}
