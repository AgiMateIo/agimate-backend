package ru.agimate.deviceapi.security;

import java.security.Principal;
import java.util.UUID;

public record DevicePrincipal(
        String name,
        UUID deviceAuthPubId,
        UUID userPubId
) implements Principal {

    @Override
    public String getName() {
        return name;
    }
}
