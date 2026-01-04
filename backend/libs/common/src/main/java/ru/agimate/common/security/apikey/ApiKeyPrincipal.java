package ru.agimate.common.security.apikey;

import java.security.Principal;

/**
 * Principal for API key authenticated requests in connectors-api.
 * Contains information about the connector API key and associated user.
 */
public record ApiKeyPrincipal(
        String pubId,
        String userPubId
) implements Principal {

    @Override
    public String getName() {
        return userPubId;
    }
}