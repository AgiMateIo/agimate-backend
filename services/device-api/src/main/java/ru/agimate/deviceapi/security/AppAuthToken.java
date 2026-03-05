package ru.agimate.deviceapi.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

public class AppAuthToken implements Authentication {

    private final AppPrincipal principal;
    private final Collection<? extends GrantedAuthority> authorities;
    private boolean authenticated = true;

    public AppAuthToken(AppPrincipal principal,
                        Collection<? extends GrantedAuthority> authorities) {
        this.principal = principal;
        this.authorities = authorities;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public Object getCredentials() {
        return principal.appPubId();
    }

    @Override
    public Object getDetails() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public boolean isAuthenticated() {
        return authenticated;
    }

    @Override
    public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {
        this.authenticated = isAuthenticated;
    }

    @Override
    public String getName() {
        return principal.name();
    }
}
