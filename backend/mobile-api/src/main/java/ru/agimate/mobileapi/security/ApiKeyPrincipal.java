package ru.agimate.mobileapi.security;

import java.security.Principal;

public record ApiKeyPrincipal(
        String name,
        String apiKey
) implements Principal {

    @Override
    public String getName() {
        return name;
    }
}
