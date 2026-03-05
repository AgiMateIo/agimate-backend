package ru.agimate.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.agimate.common.rest.error.UnauthorizedStatusException;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;

import java.util.UUID;

/**
 * Utility methods for working with security context.
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * Get current authenticated user's public ID from JWT token.
     *
     * @return user public ID
     * @throws UnauthorizedStatusException if user is not authenticated or principal is wrong type
     */
    public static UUID getCurrentUserPubId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedStatusException("User is not authenticated");
        }

        Object principal = authentication.getPrincipal();

        if (principal instanceof AgimateUserPrincipal userPrincipal) {
            return UUID.fromString(userPrincipal.getName());
        }

        throw new UnauthorizedStatusException("Invalid authentication type");
    }

}
