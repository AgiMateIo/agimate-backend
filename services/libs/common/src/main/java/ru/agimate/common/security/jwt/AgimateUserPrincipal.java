package ru.agimate.common.security.jwt;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.agimate.common.security.UserRole;

import java.security.Principal;
import java.util.Collection;
import java.util.List;

public record AgimateUserPrincipal(
        String id,
        Collection<? extends GrantedAuthority> authorities
) implements Principal {

    public static final Collection<? extends SimpleGrantedAuthority> DEFAULT_ROLES = List.of(new SimpleGrantedAuthority("ROLE_USER"));

    public AgimateUserPrincipal(String id) {
        this(id, DEFAULT_ROLES);
    }

    public static AgimateUserPrincipal fromUser(String id, UserRole role) {
        return new AgimateUserPrincipal(id, List.of(new SimpleGrantedAuthority(role.toAuthority())));
    }

    public boolean isAdmin() {
        return authorities != null && authorities.stream()
                .anyMatch(a -> UserRole.ADMIN.toAuthority().equals(a.getAuthority()));
    }

    @Override
    public String getName() {
        return this.id;
    }
}
