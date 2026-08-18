package ru.agimate.common.security.jwt;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.agimate.common.security.UserRole;

import java.security.Principal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * @param authSessionId the sign-in this token was minted for ({@code auth_sessions.id} in user-api),
 *                      null in every context that predates the claim or has no session behind it —
 *                      an agent key, a token issued before {@code asid} shipped. Not to be confused
 *                      with the agent session: that one addresses a conversation, this one a device
 *                      that logged in.
 */
public record AgimateUserPrincipal(
        String id,
        Collection<? extends GrantedAuthority> authorities,
        UUID authSessionId
) implements Principal {

    public static final Collection<? extends SimpleGrantedAuthority> DEFAULT_ROLES = List.of(new SimpleGrantedAuthority("ROLE_USER"));

    public AgimateUserPrincipal(String id) {
        this(id, DEFAULT_ROLES, null);
    }

    public AgimateUserPrincipal(String id, Collection<? extends GrantedAuthority> authorities) {
        this(id, authorities, null);
    }

    public static AgimateUserPrincipal fromUser(String id, UserRole role) {
        return fromUser(id, role, null);
    }

    public static AgimateUserPrincipal fromUser(String id, UserRole role, UUID authSessionId) {
        return new AgimateUserPrincipal(id, List.of(new SimpleGrantedAuthority(role.toAuthority())), authSessionId);
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
