package ru.agimate.userapi.service.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.userapi.database.entities.AuthClient;
import ru.agimate.userapi.database.entities.PendingRegistration;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.security.oauth2.CookieOAuth2AuthorizationRequestRepository;
import ru.agimate.userapi.service.UserService;
import ru.agimate.userapi.service.mail.MailService;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Registering with a password: asking for an account, and then showing that the address is yours.
 *
 * <p>Nothing lands in {@code users} before the letter is opened. An account created earlier could be
 * claimed — register with somebody else's address, leave it unconfirmed, and their later sign-in
 * through a provider joins them to an account that already carries a password. Keeping the request
 * apart preserves the rule the provider login rests on: a row in {@code users} is an address
 * somebody has proved (docs/decisions/email-password-auth.md).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private static final String CONFIRM_LETTER = "registration-confirm";
    private static final String EXISTS_LETTER = "account-exists";
    private static final String CONFIRM_PATH = "/register/confirm?token=";

    private final PendingRegistrationService pendingRegistrations;
    private final PasswordEncoder passwordEncoder;
    private final UserService userService;
    private final AuthSessionService authSessionService;
    private final PasswordAuthService passwordAuthService;
    private final MailService mailService;

    /**
     * Takes the request and tells the caller nothing about it. A free address, a taken one and a
     * mailbox that has had enough letters are one answer — the difference is only of use to somebody
     * finding out who is registered here.
     *
     * <p>No password is taken here: it is chosen at confirmation, by whoever opens the letter. That
     * is what keeps an unsolicited click from creating an account whose password somebody else
     * picked — the form and the mailbox are not necessarily the same person.
     *
     * @param referralCode the code the visitor arrived with, or null; honoured only if this request
     *                     ends up creating an account, so that a link brings new people alone
     * @param linkBase     origin of the frontend the request came from, so the letter points at the
     *                     installation the person is actually using
     */
    public void register(String rawEmail, @Nullable String displayName,
                         @Nullable String referralCode, String linkBase) {
        String email = UserService.fold(rawEmail);

        if (userService.findByEmail(email).isPresent()) {
            // Not an error: the person is told by mail that they already have a way in, and given the
            // link that adds a password to it. Answering "taken" here would enumerate addresses.
            log.debug("registration asked for an address that already has an account");
            passwordAuthService.requestReset(email, linkBase, EXISTS_LETTER);
            return;
        }

        if (!pendingRegistrations.allowedToSend(email)) {
            log.info("registration letters throttled for one address");
            return;
        }

        String token = pendingRegistrations.issue(email, displayName, resolveReferrer(referralCode));
        sendConfirmation(email, displayName, token, linkBase);
    }

    /**
     * Sends the letter again for a request that is still waiting — the commonest thing to go wrong
     * with any of this is a letter that does not arrive. Silent about everything, for the same reason
     * {@link #register} is.
     */
    public void resend(String rawEmail, String linkBase) {
        String email = UserService.fold(rawEmail);
        Optional<PendingRegistration> live = pendingRegistrations.findLive(email);
        if (live.isEmpty() || !pendingRegistrations.allowedToSend(email)) {
            return;
        }

        PendingRegistration pending = live.get();
        // A new token rather than the old one: what went out the first time exists in a letter we do
        // not have a copy of, and only its hash is here.
        String token = pendingRegistrations.issue(pending.getEmail(), pending.getDisplayName(),
                pending.getReferredBy());

        sendConfirmation(email, pending.getDisplayName(), token, linkBase);
    }

    /**
     * The moment the address becomes proved. Ends with a session rather than with a redirect to the
     * login page: the person has just shown everything the account could ask of them.
     */
    @Transactional
    public IssuedTokens confirm(String token, String password, AuthClient client,
                                @Nullable String deviceLabel) {
        PasswordPolicy.validate(password);
        PendingRegistration pending = pendingRegistrations.consume(token);
        String passwordHash = passwordEncoder.encode(password);

        UserEntity user = userService.findByEmail(pending.getEmail())
                // Somebody signed in through a provider while this letter was waiting. The address is
                // theirs either way — this letter proves it as surely as the provider did — so the
                // password joins the account that exists instead of colliding with its unique index.
                .map(existing -> attachPassword(existing, passwordHash))
                .orElseGet(() -> create(pending, passwordHash));

        return authSessionService.open(user, client, deviceLabel);
    }

    /**
     * Safe precisely because the password was named a moment ago by whoever opened the letter: they
     * have proved the mailbox the account is bound to and chosen the password themselves. Other
     * sessions are left alone — nothing here suggests the account is in anybody else's hands, unlike
     * a reset, which is asked for by somebody who has lost their way in.
     */
    private UserEntity attachPassword(UserEntity user, String passwordHash) {
        log.info("address gained an account while its registration waited — password attached to {}",
                user.getId());
        user.setPasswordHash(passwordHash);
        user.setPasswordUpdatedAt(LocalDateTime.now());
        return userService.updateUser(user);
    }

    private UserEntity create(PendingRegistration pending, String passwordHash) {
        UserEntity user = userService.createUser(pending.getEmail(), null, null,
                displayName(pending.getDisplayName(), pending.getEmail()), pending.getReferredBy());
        user.setPasswordHash(passwordHash);
        user.setPasswordUpdatedAt(LocalDateTime.now());

        log.info("account {} created from a confirmed registration", user.getId());
        return userService.updateUser(user);
    }

    private void sendConfirmation(String email, @Nullable String displayName, String token,
                                  String linkBase) {
        mailService.send(email, CONFIRM_LETTER, Map.of(
                "name", displayName(displayName, email),
                "link", linkBase + CONFIRM_PATH + token,
                "hours", String.valueOf(PendingRegistrationService.CONFIRM_TTL.toHours())));
    }

    private static String displayName(@Nullable String displayName, String email) {
        return displayName == null || displayName.isBlank() ? email : displayName;
    }

    /**
     * An unknown code is a typo in a link or a campaign that outlived its owner: logged and dropped,
     * never a reason to turn a registration away. Resolved now rather than at confirmation, so that
     * the foreign key vouches for it for as long as the request waits.
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
