package ru.agimate.userapi.service.auth;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.TooManyRequestsStatusException;
import ru.agimate.common.rest.error.UnauthorizedStatusException;
import ru.agimate.userapi.database.entities.AuthClient;
import ru.agimate.userapi.database.entities.AuthTokenPurpose;
import ru.agimate.userapi.database.entities.SessionRevokeReason;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.service.UserService;
import ru.agimate.userapi.service.mail.MailService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Signing in with a password, and the letter that is the only way to acquire one.
 *
 * <p>Setting a password and resetting it are deliberately the same operation: both are answered by
 * proving that the mailbox is yours, which is exactly what a provider vouches for with a verified
 * address. Handing out a password from inside a session instead would turn a stolen access token —
 * hours of access — into a permanent one that survives the revocation of every session.
 *
 * <p>Not annotated read-only at class level, unlike the other services here: issuing a token and
 * posting the letter about it must not share a transaction, or a rollback leaves a letter pointing
 * at a link that never existed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordAuthService {

    /** Long enough to read the letter, short enough that a forwarded one is not a spare key. */
    private static final Duration RESET_TTL = Duration.ofHours(1);

    private static final Duration MAIL_INTERVAL = Duration.ofMinutes(1);
    private static final Duration MAIL_WINDOW = Duration.ofHours(1);
    private static final int MAIL_LIMIT = 5;

    private static final String RESET_LETTER = "password-reset";
    private static final String RESET_PATH = "/password/reset?token=";

    private final PasswordEncoder passwordEncoder;
    private final UserService userService;
    private final AuthTokenService authTokenService;
    private final AuthSessionService authSessionService;
    private final MailService mailService;
    private final LoginRateLimiter rateLimiter;

    /**
     * A hash of a value nobody knows, encoded once at startup. Generated rather than written down:
     * a constant hash in the source is a constant to explain to every reader after.
     */
    private String absentPasswordHash;

    @PostConstruct
    void prepareAbsentPasswordHash() {
        absentPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * An unknown mailbox and a wrong password are refused identically, and both cost the same bcrypt
     * comparison: the answer must not tell whether an address is registered here, and neither must
     * the time it takes to arrive.
     */
    public IssuedTokens login(String email, String password, AuthClient client,
                              @Nullable String deviceLabel) {
        if (rateLimiter.blocked(email)) {
            throw new TooManyRequestsStatusException("Too many attempts — try again in "
                    + rateLimiter.window().toMinutes() + " minutes");
        }

        Optional<UserEntity> candidate = userService.findByEmail(email)
                .filter(user -> user.getPasswordHash() != null);

        if (!matches(password, candidate.map(UserEntity::getPasswordHash).orElse(null))) {
            rateLimiter.recordFailure(email);
            throw new UnauthorizedStatusException("Invalid email or password");
        }

        rateLimiter.recordSuccess(email);
        return authSessionService.open(candidate.orElseThrow(), client, deviceLabel);
    }

    /**
     * Sends the letter that leads to a password — whether the person forgot theirs or never had one.
     * Silent about everything: an unknown address, a throttled mailbox and a letter on its way are
     * one and the same answer to the caller, because the difference is only useful to somebody
     * collecting addresses.
     *
     * @param linkBase origin of the frontend the request came from, so the letter points at the
     *                 installation the person is actually using
     */
    public void requestReset(String email, String linkBase) {
        requestReset(email, linkBase, RESET_LETTER);
    }

    /**
     * @param letter which letter carries the link. A person whose address is already taken asked to
     *               register, not to reset — they are told that the account exists, and the link is
     *               the same one, because adding a password and resetting it are the same operation
     */
    public void requestReset(String email, String linkBase, String letter) {
        Optional<UserEntity> found = userService.findByEmail(email);
        if (found.isEmpty()) {
            log.debug("password reset asked for an address with no account");
            return;
        }

        UserEntity user = found.get();
        if (!authTokenService.allowedToSend(user.getId(), AuthTokenPurpose.PASSWORD_RESET,
                MAIL_INTERVAL, MAIL_WINDOW, MAIL_LIMIT)) {
            log.info("password reset throttled for user {}", user.getId());
            return;
        }

        String token = authTokenService.issue(user.getId(), AuthTokenPurpose.PASSWORD_RESET, RESET_TTL);
        mailService.send(user.getEmail(), letter, Map.of(
                "name", displayName(user),
                "link", linkBase + RESET_PATH + token,
                "hours", String.valueOf(RESET_TTL.toHours())));
    }

    /**
     * Ends every session of the account, the one asking included: a person who does not know their
     * password has no session worth keeping, and the likelier reason they are here is that somebody
     * else has one.
     */
    @Transactional
    public void reset(String token, String password) {
        PasswordPolicy.validate(password);

        UUID userId = authTokenService.consume(token, AuthTokenPurpose.PASSWORD_RESET);
        UserEntity user = userService.findById(userId)
                .orElseThrow(() -> new ForbiddenStatusException("User no longer exists"));

        store(user, password);
        authSessionService.revokeAllForUser(userId, SessionRevokeReason.PASSWORD_CHANGED, null);
        log.info("password set for user {} through a reset link", userId);
    }

    /**
     * @param currentSessionId the sign-in doing the changing; it survives, every other one does not.
     *                         Null only for a token minted before sessions were recorded
     */
    @Transactional
    public void change(UUID userId, @Nullable UUID currentSessionId, String currentPassword,
                       String newPassword) {
        UserEntity user = userService.findById(userId)
                .orElseThrow(() -> new ForbiddenStatusException("User no longer exists"));

        if (user.getPasswordHash() == null) {
            throw new BadRequestStatusException("This account has no password yet — ask for one by "
                    + "mail through the password reset");
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BadRequestStatusException("Current password does not match");
        }
        PasswordPolicy.validate(newPassword);

        store(user, newPassword);
        authSessionService.revokeAllForUser(userId, SessionRevokeReason.PASSWORD_CHANGED, currentSessionId);
        log.info("password changed by user {}", userId);
    }

    private void store(UserEntity user, String password) {
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setPasswordUpdatedAt(LocalDateTime.now());
        userService.updateUser(user);
    }

    /**
     * The comparison happens even when there is nobody to compare against, against a hash of a value
     * no one knows: skipping it would answer an unregistered address noticeably faster than a wrong
     * password, and that difference is the enumeration this endpoint refuses to allow.
     */
    private boolean matches(String password, @Nullable String hash) {
        if (hash == null) {
            passwordEncoder.matches(password, absentPasswordHash);
            return false;
        }
        return passwordEncoder.matches(password, hash);
    }

    private static String displayName(UserEntity user) {
        return user.getDisplayName() != null && !user.getDisplayName().isBlank()
                ? user.getDisplayName()
                : user.getEmail();
    }
}
