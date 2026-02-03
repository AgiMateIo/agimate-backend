package ru.agimate.mobileapi.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.agimate.common.rest.error.UnauthorizedStatusException;

import java.util.UUID;

public class DeviceSecurityUtils {

    /**
     * Get current API key's user public ID.
     *
     * @return user public ID associated with the API key
     * @throws UnauthorizedStatusException if API key is not authenticated
     */
    public static UUID getApiKeyUserPubId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedStatusException("API key is not authenticated");
        }

        if (authentication instanceof DeviceAuthenticationToken deviceAuthenticationToken) {
            DevicePrincipal principal = (DevicePrincipal) deviceAuthenticationToken.getPrincipal();
            return principal.deviceAuthPubId();
        }

        throw new UnauthorizedStatusException("Invalid authentication type");
    }
}
