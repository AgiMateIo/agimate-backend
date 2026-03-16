package ru.agimate.common.security.jwt;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.agimate.common.security.UserRole;

import java.security.Principal;
import java.util.Collection;
import java.util.List;

public record AgimateUserPrincipal(
        String pubId,
        Collection<? extends GrantedAuthority> authorities
) implements Principal {

    public static final Collection<? extends SimpleGrantedAuthority> DEFAULT_ROLES = List.of(new SimpleGrantedAuthority("ROLE_USER"));

    public AgimateUserPrincipal(String pubId) {
        this(pubId, DEFAULT_ROLES);
    }

    public static AgimateUserPrincipal fromUser(String pubId, UserRole role) {
        return new AgimateUserPrincipal(pubId, List.of(new SimpleGrantedAuthority(role.toAuthority())));
    }

    @Override
    public String getName() {
        return this.pubId;
    }
}