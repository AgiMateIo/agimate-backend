package ru.agimate.userapi.service.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.userapi.database.entities.OAuthProviderType;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.database.entities.ProviderLinkProof;
import ru.agimate.userapi.database.entities.UserOAuthAccount;
import ru.agimate.userapi.database.repositories.UserOAuthAccountRepository;
import ru.agimate.userapi.service.UserService;
import ru.agimate.userapi.service.mail.MailService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The ways into one account, and the rules for adding and removing them.
 *
 * <p>What the account's settings can do: read the list, add a provider that a sign-in could never
 * have added by itself — one whose mailbox is a different one, or none — and take a way in away.
 *
 * <p>Whose a provider identity is and what may be written to {@code user_oauth_accounts} is not
 * decided here: {@link ProviderIdentityService} owns both, and this delegates to it. Two accounts
 * are never merged — a provider that already belongs to somebody else is refused, because merging
 * would mean deciding what happens to two sets of agents, connections and files, and that is a
 * feature, not a branch in a linking routine.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginMethodService {

    private static final String UNLINKED_LETTER = "provider-unlinked";
    private static final String PASSWORD_REMOVED_LETTER = "password-removed";

    private final UserOAuthAccountRepository oAuthAccountRepository;
    private final ProviderIdentityService providerIdentityService;
    private final ProviderLinkProofService linkProofService;
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
     * Finishes a binding the person started in their settings: the provider is whatever the round
     * trip proved, the account is the one this call is authenticated as.
     *
     * <p>That order is the security of it. The round trip is begun by sending a browser somewhere,
     * which anybody can cause; this request carries an access token in a header, which only the
     * account holder's own page can send. So the trip establishes a provider and nothing more, and
     * the account is named by something no other origin can forge.
     *
     * @param proof what came back on {@code link_proof}; spent here and worthless afterwards
     */
    @Transactional
    public LinkResult redeemLinkProof(UUID userId, String proof) {
        ProviderLinkProof proven = linkProofService.consume(proof);

        ProviderIdentityService.LinkOutcome outcome = providerIdentityService.bind(userId,
                proven.getOauthProvider(), proven.getProviderUserId(), proven.getEmail(),
                proven.getFirstName(), proven.getLastName());

        return new LinkResult(proven.getOauthProvider(), outcome);
    }

    /** Which provider the proof turned out to be for, and what came of binding it. */
    public record LinkResult(OAuthProviderType provider, ProviderIdentityService.LinkOutcome outcome) {}

    @Transactional
    public void unlinkProvider(UUID userId, OAuthProviderType provider) {
        UserEntity user = lockUser(userId);
        UserOAuthAccount account = oAuthAccountRepository
                .findByUserEntityIdAndOauthProvider(userId, provider).stream().findFirst()
                .orElseThrow(() -> new NotFoundStatusException("This account has no " + provider + " linked"));

        requireAnotherWayIn(user);
        oAuthAccountRepository.delete(account);

        log.info("{} unlinked from user {}", provider, userId);
        notify(user, UNLINKED_LETTER, provider.getDisplayName());
    }

    @Transactional
    public void dropPassword(UUID userId) {
        UserEntity user = lockUser(userId);
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

    /**
     * Counting the ways in and removing one are two statements, and between them another request can
     * remove the other one — two devices, two taps, an account with nothing left to sign in with.
     * The row is locked so that the second of them waits and then counts what the first has left.
     */
    private UserEntity lockUser(UUID userId) {
        return userService.findByIdForUpdate(userId)
                .orElseThrow(() -> new ForbiddenStatusException("User no longer exists"));
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
