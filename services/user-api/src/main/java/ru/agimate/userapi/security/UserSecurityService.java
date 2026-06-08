package ru.agimate.userapi.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.userapi.database.repositories.UserRepository;

import java.util.UUID;

@Service("userSecurityService")
@RequiredArgsConstructor
public class UserSecurityService {

    private final UserRepository userRepository;

    public boolean canAccessUser(Authentication authentication, UUID requestedId) {
        // If no authentication, access is denied
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        // Extract the authenticated user's information
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof AgimateUserPrincipal agimateUserPrincipal)) {
            return false;
        }

        UUID authenticatedUserId = UUID.fromString(agimateUserPrincipal.id());

        // Allow access if:
        // 1. The requested user is the same as the authenticated user (self-access)
        // 2. The authenticated user has ADMIN role
        return authenticatedUserId.equals(requestedId) ||
               authentication.getAuthorities().stream()
                   .anyMatch(grantedAuthority ->
                       "ROLE_ADMIN".equals(grantedAuthority.getAuthority()));
    }
}
