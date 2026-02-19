package ru.agimate.deviceapi.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.agimate.common.rest.error.UnauthorizedStatusException;

import java.util.UUID;

public class AppSecurityUtils {

    public static UUID getApiKeyUserPubId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedStatusException("API key is not authenticated");
        }

        if (authentication instanceof AppAuthenticationToken appAuthenticationToken) {
            AppPrincipal principal = (AppPrincipal) appAuthenticationToken.getPrincipal();
            return principal.appPubId();
        }

        throw new UnauthorizedStatusException("Invalid authentication type");
    }
}
