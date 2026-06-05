package ru.agimate.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.agimate.common.rest.error.UnauthorizedStatusException;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Utility methods for working with security context.
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * Get current authenticated user's id from JWT token.
     *
     * @return user id
     * @throws UnauthorizedStatusException if user is not authenticated or principal is wrong type
     */
    public static UUID getCurrentUserId() {
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

    /**
     * Human-readable rendering of the current authentication's roles for inclusion in
     * Access-Denied diagnostics. Returns role names without the {@code ROLE_} prefix,
     * comma-separated (e.g. {@code "GUEST"}, {@code "GUEST, USER"}), or {@code "anonymous"}
     * when there is no authentication or no granted authorities.
     */
    public static String describeCurrentRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null || authentication.getAuthorities().isEmpty()) {
            return "anonymous";
        }
        String rendered = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .collect(Collectors.joining(", "));
        return rendered.isEmpty() ? "anonymous" : rendered;
    }

    /**
     * Builds the standard "Access denied" error message enriched with the current user's role(s),
     * e.g. {@code "Access denied. Insufficient permissions (current role: GUEST)."}.
     */
    public static String accessDeniedMessage() {
        return "Access denied. Insufficient permissions (current role: " + describeCurrentRoles() + ").";
    }

}
