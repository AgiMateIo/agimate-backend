package ru.agimate.userapi.service.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.userapi.database.entities.AuthTokenPurpose;
import ru.agimate.userapi.database.entities.OAuthProviderType;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.database.entities.UserOAuthAccount;
import ru.agimate.userapi.database.repositories.UserOAuthAccountRepository;
import ru.agimate.userapi.service.UserService;
import ru.agimate.userapi.service.mail.MailService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The ways into one account, and the rules for adding and removing them.
 *
 * <p>Several providers already lead to one person: a login through a second provider finds the
 * account by its verified address and joins it. What lives here is the case that cannot work by
 * address — a provider whose mailbox is a different one — and the management around it.
 *
 * <p>Two accounts are never merged. A provider that already belongs to somebody else is refused,
 * because merging would mean deciding what happens to two sets of agents, connections and files, and
 * that is a feature, not a branch in a linking routine.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginMethodService {

    /** Long enough for the trip to a provider and back, short enough to be worthless if captured. */
    private static final Duration LINK_TICKET_TTL = Duration.ofMinutes(5);

    private static final String LINKED_LETTER = "provider-linked";
    private static final String UNLINKED_LETTER = "provider-unlinked";
    private static final String PASSWORD_REMOVED_LETTER = "password-removed";

    private final UserOAuthAccountRepository oAuthAccountRepository;
    private final AuthTokenService authTokenService;
    private final UserService userService;
    private final MailService mailService;

    /** What the account's settings show: one entry per way in, oldest first, password last. */
    public List<LoginMethod> list(UUID userId) {
        UserEntity user = requireUser(userId);

        List<LoginMethod> methods = new ArrayList<>();
        for (UserOAuthAccount account : oAuthAccountRepository.findByUserEntityIdOrderByCreatedAtAsc(userId)) {
            methods.add(LoginMethod.ofProvider(account));
        }
        if (user.getPasswordHash() != null) {
            methods.add(LoginMethod.ofPassword(user.getPasswordUpdatedAt()));
        }
        return methods;
    }

    /**
     * @return the ticket, which the client hands to {@code /oauth2/authorization/{provider}} and
     *         nothing else ever sees
     */
    public String issueLinkTicket(UUID userId) {
        return authTokenService.issue(userId, AuthTokenPurpose.PROVIDER_LINK, LINK_TICKET_TTL);
    }

    /** What became of a linking attempt, in the terms the redirect back to the client speaks. */
    public enum LinkOutcome { LINKED, ALREADY_YOURS, TAKEN }

    /**
     * Binds a provider account to the person the ticket was issued to. The address the provider
     * reports is not consulted: identity here is the account you were signed into, which is exactly
     * what makes this work for a provider whose mailbox is a different one — or none.
     *
     * @param email what the provider says the address is, or null; kept for the listing alone
     */
    @Transactional
    public LinkOutcome link(String ticket, OAuthProviderType provider, String providerUserId,
                            @Nullable String email, @Nullable String firstName, @Nullable String lastName) {
        UUID userId = authTokenService.consume(ticket, AuthTokenPurpose.PROVIDER_LINK);
        UserEntity user = requireUser(userId);

        var existing = oAuthAccountRepository.findByOauthProviderAndProviderUserIdWithUser(provider, providerUserId);
        if (existing.isPresent()) {
            if (existing.get().getUserEntity().getId().equals(userId)) {
                return LinkOutcome.ALREADY_YOURS;
            }
            log.warn("{} account already belongs to another user — refusing to link it to {}",
                    provider, userId);
            return LinkOutcome.TAKEN;
        }

        oAuthAccountRepository.save(UserOAuthAccount.builder()
                .userEntity(user)
                .oauthProvider(provider)
                .providerUserId(providerUserId)
                .email(email)
                .firstName(firstName)
                .lastName(lastName)
                .build());

        log.info("{} linked to user {}", provider, userId);
        notify(user, LINKED_LETTER, provider.getDisplayName());
        return LinkOutcome.LINKED;
    }

    @Transactional
    public void unlinkProvider(UUID userId, OAuthProviderType provider) {
        UserEntity user = requireUser(userId);
        UserOAuthAccount account = oAuthAccountRepository
                .findByUserEntityIdAndOauthProvider(userId, provider)
                .orElseThrow(() -> new NotFoundStatusException("This account has no " + provider + " linked"));

        requireAnotherWayIn(user);
        oAuthAccountRepository.delete(account);

        log.info("{} unlinked from user {}", provider, userId);
        notify(user, UNLINKED_LETTER, provider.getDisplayName());
    }

    @Transactional
    public void dropPassword(UUID userId) {
        UserEntity user = requireUser(userId);
        if (user.getPasswordHash() == null) {
            throw new NotFoundStatusException("This account has no password");
        }

        requireAnotherWayIn(user);
        user.setPasswordHash(null);
        user.setPasswordUpdatedAt(null);
        userService.updateUser(user);

        log.info("password removed from user {}", userId);
        notify(user, PASSWORD_REMOVED_LETTER, null);
    }

    /**
     * The last way in cannot be removed. Without this the commonest support request would be somebody
     * who unlinked their only provider and is now outside their own account, and nothing short of an
     * administrator could let them back in.
     */
    private void requireAnotherWayIn(UserEntity user) {
        long methods = oAuthAccountRepository.countByUserEntityId(user.getId())
                + (user.getPasswordHash() == null ? 0 : 1);
        if (methods <= 1) {
            throw new BadRequestStatusException(
                    "This is the only way into the account — add another one before removing this");
        }
    }

    /**
     * A way in appearing or disappearing is exactly what somebody who has taken over an account does
     * first, and the owner's mailbox is the one place they cannot quietly reach.
     */
    private void notify(UserEntity user, String letter, @Nullable String provider) {
        Map<String, String> variables = provider == null
                ? Map.of("name", displayName(user))
                : Map.of("name", displayName(user), "provider", provider);
        mailService.send(user.getEmail(), letter, variables);
    }

    private UserEntity requireUser(UUID userId) {
        return userService.findById(userId)
                .orElseThrow(() -> new ForbiddenStatusException("User no longer exists"));
    }

    private static String displayName(UserEntity user) {
        return user.getDisplayName() != null && !user.getDisplayName().isBlank()
                ? user.getDisplayName()
                : user.getEmail();
    }

    /** One way into an account, whichever kind it is. */
    public record LoginMethod(Kind kind,
                              @Nullable OAuthProviderType provider,
                              @Nullable String email,
                              @Nullable LocalDateTime addedAt) {

        public enum Kind { PASSWORD, OAUTH }

        static LoginMethod ofProvider(UserOAuthAccount account) {
            return new LoginMethod(Kind.OAUTH, account.getOauthProvider(), account.getEmail(),
                    account.getCreatedAt());
        }

        static LoginMethod ofPassword(@Nullable LocalDateTime changedAt) {
            return new LoginMethod(Kind.PASSWORD, null, null, changedAt);
        }
    }
}
