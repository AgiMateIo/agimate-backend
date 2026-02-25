package ru.agimate.deviceapi.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.agimate.common.rest.error.UnauthorizedStatusException;

import java.util.UUID;

public class ConnectorSecurityUtils {

    public static UUID getConnectorUserPubId() {
        return getPrincipal().userPubId();
    }

    public static UUID getConnectorPubId() {
        return getPrincipal().connectorPubId();
    }

    public static ConnectorPrincipal getPrincipal() {
        return getPrincipal(SecurityContextHolder.getContext().getAuthentication());
    }

    public static ConnectorPrincipal getPrincipal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedStatusException("Connector key is not authenticated");
        }

        if (authentication instanceof ConnectorAuthToken connectorAuthToken) {
            return (ConnectorPrincipal) connectorAuthToken.getPrincipal();
        }

        throw new UnauthorizedStatusException("Invalid authentication type");
    }
}
