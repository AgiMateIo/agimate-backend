package ru.agimate.common.security.apikey;

import java.security.Principal;
import java.util.UUID;

/**
 * Principal for API key authenticated requests in connectors-api.
 * Contains information about the connector API key and associated user.
 */
public record ApiKeyPrincipal(
        String uuid
) implements Principal {

    @Override
    public String getName() {
        return uuid;
    }
}