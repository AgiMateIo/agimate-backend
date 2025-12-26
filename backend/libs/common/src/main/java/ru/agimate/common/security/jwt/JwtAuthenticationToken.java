package ru.agimate.common.security.jwt;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * Custom authentication token for JWT-based authentication.
 * This represents a JWT-authenticated user in the security context.
 */
public class JwtAuthenticationToken implements Authentication {

    private final AgimateUserPrincipal principal;
    private final String credentials;
    private boolean authenticated = false;

    /**
     * Creates an authenticated JWT token
     */
    public JwtAuthenticationToken(
            AgimateUserPrincipal principal
    ) {
        this.principal = principal;
        this.credentials = null; // JWT token is not stored as credentials in the security context
        this.authenticated = true;
    }


    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return this.principal.authorities();
    }

    @Override
    public Object getCredentials() {
        return this.credentials;
    }

    @Override
    public Object getDetails() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return this.principal;
    }

    @Override
    public boolean isAuthenticated() {
        return this.authenticated;
    }

    @Override
    public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {
        this.authenticated = isAuthenticated;
    }

    @Override
    public String getName() {
        return principal.getName();
    }
}
