package ru.agimate.userapi.service.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.userapi.database.entities.OAuthProviderType;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.database.entities.UserOAuthAccount;
import ru.agimate.userapi.database.repositories.UserOAuthAccountRepository;
import ru.agimate.userapi.security.oauth2.CookieOAuth2AuthorizationRequestRepository;
import ru.agimate.userapi.security.oauth2.OAuthLoginException;
import ru.agimate.userapi.security.oauth2.providers.OAuthUserAdapter;
import ru.agimate.userapi.security.oauth2.providers.OAuthUserInfo;
import ru.agimate.userapi.service.UserService;
import ru.agimate.userapi.service.mail.MailService;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Who a provider identity belongs to, and the only place that writes {@code user_oauth_accounts}.
 *
 * <p>It exists because that question used to have three answers in three files: the login joined by
 * address, the linking callback joined by ticket, and each of them carried its own idea of what was
 * allowed. One of the two knew about {@code uq_user_oauth_accounts_user_id_oauth_provider} and the
 * other did not, so an ordinary login could end in a constraint violation and a 500. A table with
 * rules needs somewhere for the rules to live.
 *
 * <h2>What identifies a person</h2>
 *
 * <p>The pair {@code (provider, providerUserId)} and nothing else — OpenID Connect Core §5.7 is
 * explicit that an address must not be used as a unique identifier for the end user, because it is
 * neither stable nor exclusively the provider's to assign. Everything below follows from that: the
 * pair decides on its own, and the address is only ever a convenience for the one case it can serve.
 *
 * <h2>The address as a convenience, declared rather than assumed</h2>
 *
 * <p>Signing in through a second provider on the same mailbox and landing in the account you already
 * have is worth keeping — it is what makes «Google, and later Yandex» feel like one person. But it
 * rests entirely on the provider being honest about verification, and two of the four adapters here
 * answer {@code emailVerified} with a literal {@code true} on reasoning about how that provider
 * works, which is a claim about somebody else's system written where nobody will look at it again.
 *
 * <p>So the right to join an existing account is
 * {@link OAuthUserAdapter#joinsExistingAccountByAddress() declared by the adapter} and absent by
 * default. A provider that cannot answer for an address may still open an account with one; it may
 * never walk into an account that is already there. The four adapters shipped here all declare it,
 * so nothing changes for them — what changes is the fifth, written by whoever runs their own
 * installation, which is now safe before anybody reviews it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProviderIdentityService {

    private static final String LINKED_LETTER = "provider-linked";

    private final UserOAuthAccountRepository oAuthAccountRepository;
    private final UserService userService;
    private final MailService mailService;

    /** What became of a binding, in the terms the caller reports back to a person. */
    public enum LinkOutcome {
        LINKED,
        /** The same provider account, bound already. Saying so beats inventing an error. */
        ALREADY_YOURS,
        /** That account of that provider belongs to somebody else, and accounts are never merged. */
        TAKEN,
        /** A different account of a provider this person already has. One per provider, by design. */
        PROVIDER_OCCUPIED
    }

    /**
     * The sign-in path: the account behind a provider identity, opening one if the address is new to
     * this installation.
     *
     * <p>Three answers in a fixed order, and the order is the security argument. The pair decides
     * whenever it can. Only when it cannot is the address consulted, and only then does it matter
     * whether the provider is allowed to speak for one.
     *
     * @param referralCode the code the visitor arrived with, or null; honoured only when this call
     *                     ends up creating an account, so that a link brings new people alone
     * @throws OAuthLoginException when the provider gave nothing to go on, or when the address leads
     *                             to an account this provider may not join
     */
    @Transactional
    public UserEntity resolve(OAuthUserAdapter adapter, OAuthUserInfo userInfo,
                              @Nullable String referralCode) {
        OAuthProviderType provider = adapter.providerType();

        Optional<UserOAuthAccount> bound = oAuthAccountRepository
                .findByOauthProviderAndProviderUserIdWithUser(provider, userInfo.providerUserId());
        if (bound.isPresent()) {
            return bound.get().getUserEntity();
        }

        String email = requireEmail(adapter, userInfo);
        Optional<UserEntity> byAddress = userService.findByEmail(email);

        if (byAddress.isEmpty()) {
            UserEntity created = userService.createUser(email, userInfo.firstName(), userInfo.lastName(),
                    StringUtils.hasText(userInfo.displayName()) ? userInfo.displayName() : email,
                    resolveReferrer(referralCode));
            insert(created, provider, userInfo.providerUserId(), email,
                    userInfo.firstName(), userInfo.lastName());
            return created;
        }

        return join(adapter, byAddress.get(), userInfo, email);
    }

    /**
     * The linking path: bind a provider identity to a named account. The address the provider reports
     * is not consulted and is kept for the listing alone — identity here is the account the caller
     * proved with an access token, which is exactly what makes this work for a provider whose mailbox
     * is a different one, or none.
     */
    @Transactional
    public LinkOutcome bind(UUID userId, OAuthProviderType provider, String providerUserId,
                            @Nullable String email, @Nullable String firstName,
                            @Nullable String lastName) {
        // Locked before anything is read, the way unlinking locks it: what follows is «look, then
        // insert», and between the two another binding of the same person can land — two tabs, two
        // taps. The second one then reads what the first one left and answers PROVIDER_OCCUPIED,
        // instead of reaching the insert and raising a constraint violation, which in PostgreSQL
        // poisons the whole transaction and cannot be recovered from inside it.
        UserEntity user = userService.findByIdForUpdate(userId)
                .orElseThrow(() -> new ForbiddenStatusException("User no longer exists"));

        Optional<UserOAuthAccount> bound = oAuthAccountRepository
                .findByOauthProviderAndProviderUserIdWithUser(provider, providerUserId);
        if (bound.isPresent()) {
            if (bound.get().getUserEntity().getId().equals(userId)) {
                return LinkOutcome.ALREADY_YOURS;
            }
            log.warn("{} account already belongs to another user — refusing to bind it to {}",
                    provider, userId);
            return LinkOutcome.TAKEN;
        }

        // A second account of a provider this person already has. Everything downstream addresses a
        // provider by its name — the listing shows one row per provider, unlinking names a provider
        // — so two of a kind would show up as an ambiguous listing and an unlink that cannot resolve.
        if (!oAuthAccountRepository.findByUserEntityIdAndOauthProvider(userId, provider).isEmpty()) {
            log.info("user {} already has a {} account linked", userId, provider);
            return LinkOutcome.PROVIDER_OCCUPIED;
        }

        // The lock above does not cover the other uniqueness — two different people binding one and
        // the same provider account at the same instant, which needs two round trips through that
        // provider as that identity inside five minutes. Left to the constraint and a 500: making
        // this answer nicely would mean locking something no request here owns.
        insert(user, provider, providerUserId, email, firstName, lastName);

        notifyLinked(user, provider);
        return LinkOutcome.LINKED;
    }

    /**
     * An address that already has an account, reached through a provider the pair did not identify.
     * This is the only place a person is joined to an account they did not name, and it happens only
     * when the provider is trusted to answer for the address — see the class comment.
     */
    private UserEntity join(OAuthUserAdapter adapter, UserEntity user, OAuthUserInfo userInfo,
                            String email) {
        if (!adapter.joinsExistingAccountByAddress()) {
            log.info("{} may not join an existing account by address — refusing to sign in as {}",
                    adapter.providerType(), user.getId());
            throw new OAuthLoginException(("An account with this email address already exists. Sign "
                    + "in the way you did before, then add %s in the settings of your account.")
                    .formatted(adapter.providerType().getDisplayName()));
        }

        LinkOutcome outcome = bind(user.getId(), adapter.providerType(), userInfo.providerUserId(),
                email, userInfo.firstName(), userInfo.lastName());

        return switch (outcome) {
            case LINKED, ALREADY_YOURS -> user;
            // The address leads to an account that already reaches this provider through another of
            // its accounts. Silent before this existed, and then a constraint violation and a 500
            // once one account per provider became a rule; it is a sentence, and here it is.
            case PROVIDER_OCCUPIED -> throw new OAuthLoginException(("This email address belongs to "
                    + "an account that already signs in through a different %s account. Sign in with "
                    + "that one, or unlink it in the settings first.")
                    .formatted(adapter.providerType().getDisplayName()));
            case TAKEN -> throw new OAuthLoginException(
                    "This account of the provider is already bound to somebody else here.");
        };
    }

    /** The single insert into {@code user_oauth_accounts}; every path above ends here or nowhere. */
    private void insert(UserEntity user, OAuthProviderType provider, String providerUserId,
                        @Nullable String email, @Nullable String firstName, @Nullable String lastName) {
        oAuthAccountRepository.save(UserOAuthAccount.builder()
                .userEntity(user)
                .oauthProvider(provider)
                .providerUserId(providerUserId)
                .email(email)
                .firstName(firstName)
                .lastName(lastName)
                .build());

        log.info("{} bound to user {}", provider, user.getId());
    }

    /**
     * A way into the account appearing is exactly what somebody who has taken one over does first,
     * and the owner's mailbox is the one place they cannot quietly reach. Sent for the binding that
     * happens by itself during a sign-in as well as for the one asked for in the settings — the
     * silent one is the one worth hearing about.
     */
    private void notifyLinked(UserEntity user, OAuthProviderType provider) {
        mailService.send(user.getEmail(), LINKED_LETTER, Map.of(
                "name", user.getDisplayName() != null && !user.getDisplayName().isBlank()
                        ? user.getDisplayName()
                        : user.getEmail(),
                "provider", provider.getDisplayName()));
    }

    /**
     * The address is what ties a sign-in to an account that already exists here, so one the provider
     * does not vouch for would hand over somebody else's account to whoever claimed it.
     */
    private String requireEmail(OAuthUserAdapter adapter, OAuthUserInfo userInfo) {
        String registrationId = adapter.registrationId();
        if (!StringUtils.hasText(userInfo.email())) {
            throw new OAuthLoginException(("No email address came from %s. Add one to your %s account, "
                    + "or sign in through another provider.").formatted(registrationId, registrationId));
        }
        if (!userInfo.emailVerified()) {
            throw new OAuthLoginException(("The email address of your %s account is not confirmed. "
                    + "Confirm it there, or sign in through another provider.").formatted(registrationId));
        }
        return userInfo.email();
    }

    /**
     * Resolved inside the creating branch alone: a link can only ever bring new people, so an account
     * that already exists keeps whoever brought it here in the first place. An unknown code is a typo
     * in a link or a campaign that outlived its owner — logged and dropped, never a reason to turn a
     * sign-in away.
     */
    private @Nullable UUID resolveReferrer(@Nullable String referralCode) {
        if (!CookieOAuth2AuthorizationRequestRepository.isValidRefCode(referralCode)) {
            return null;
        }
        return userService.findByReferralCode(referralCode)
                .map(UserEntity::getId)
                .orElseGet(() -> {
                    log.warn("Unknown referral code on signup: {}", referralCode);
                    return null;
                });
    }
}
