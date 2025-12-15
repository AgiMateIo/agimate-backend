package ru.agimate.userapi.security.jwt;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * Custom authentication token for JWT-based authentication.
 * This represents a JWT-authenticated user in the security context.
 */
public class JwtAuthenticationToken implements Authentication {
    
    private final Object principal;
    private final Object credentials;
    private final Collection<? extends GrantedAuthority> authorities;
    private boolean authenticated = false;

    /**
     * Creates an authenticated JWT token
     */
    public JwtAuthenticationToken(Object principal, 
                                  Collection<? extends GrantedAuthority> authorities) {
        this.principal = principal;
        this.credentials = null; // JWT token is not stored as credentials in the security context
        this.authorities = authorities;
        this.authenticated = true;
    }

    /**
     * Creates an unauthenticated JWT token (for token validation process)
     */
    public JwtAuthenticationToken(Object principal, Object credentials) {
        this.principal = principal;
        this.credentials = credentials;
        this.authorities = null;
        this.authenticated = false;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return this.authorities;
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
        if (principal instanceof String) {
            return (String) principal;
        } else if (principal instanceof org.springframework.security.core.userdetails.User) {
            return ((org.springframework.security.core.userdetails.User) principal).getUsername();
        } else if (principal instanceof ru.agimate.userapi.security.UserPrincipal) {
            return ((ru.agimate.userapi.security.UserPrincipal) principal).getUsername();
        }
        return "unknown";
    }
}