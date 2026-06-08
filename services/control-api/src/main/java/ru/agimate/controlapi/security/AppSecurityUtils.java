package ru.agimate.controlapi.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.agimate.common.rest.error.UnauthorizedStatusException;

import java.util.UUID;

public class AppSecurityUtils {

    public static UUID getAppUserId() {
        return getPrincipal().userId();
    }

    public static UUID getAppId() {
        return getPrincipal().appId();
    }

    public static AppPrincipal getPrincipal() {
        return getPrincipal(SecurityContextHolder.getContext().getAuthentication());
    }

    public static AppPrincipal getPrincipal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedStatusException("App key is not authenticated");
        }

        if (authentication instanceof AppAuthToken appAuthToken) {
            return (AppPrincipal) appAuthToken.getPrincipal();
        }

        throw new UnauthorizedStatusException("Invalid authentication type");
    }
}
