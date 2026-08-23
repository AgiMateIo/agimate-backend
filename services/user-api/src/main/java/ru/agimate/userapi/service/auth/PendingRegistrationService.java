package ru.agimate.userapi.service.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.util.CryptoUtils;
import ru.agimate.userapi.database.entities.PendingRegistration;
import ru.agimate.userapi.database.repositories.PendingRegistrationRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * The table of registrations waiting for their letter to be opened, and the rules that make one
 * worth trusting: hashed token, spent once, superseded by the next, rationed by address.
 *
 * <p>Apart from {@link RegistrationService} for the same reason {@link AuthTokenService} is apart
 * from {@link PasswordAuthService}: writing the row and posting the letter about it must happen in
 * separate transactions, and a transaction only begins when a call crosses a bean boundary.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PendingRegistrationService {

    /** A registration letter is read later than a reset one — often the next morning. */
    static final Duration CONFIRM_TTL = Duration.ofHours(24);

    private static final Duration MAIL_INTERVAL = Duration.ofMinutes(1);
    private static final Duration MAIL_WINDOW = Duration.ofHours(1);
    private static final int MAIL_LIMIT = 5;

    private static final int TOKEN_BYTES = 32;

    private final PendingRegistrationRepository registrationRepository;

    /** @return the token, which from here on exists only in the letter */
    @Transactional
    public String issue(String email, String passwordHash, @Nullable String displayName,
                        @Nullable UUID referredBy) {
        LocalDateTime now = LocalDateTime.now();
        // Only the newest letter for an address works: an older one left live would stay a way into
        // an account long after the person stopped meaning to use it.
        registrationRepository.retireLive(email, now);

        String token = CryptoUtils.randomHex(TOKEN_BYTES);

        PendingRegistration pending = new PendingRegistration();
        pending.setEmail(email);
        pending.setPasswordHash(passwordHash);
        pending.setDisplayName(displayName);
        pending.setReferredBy(referredBy);
        pending.setTokenHash(CryptoUtils.sha256Hex(token));
        pending.setExpiresAt(now.plus(CONFIRM_TTL));
        registrationRepository.save(pending);

        return token;
    }

    /** The request still waiting for this address, if there is one. */
    public Optional<PendingRegistration> findLive(String email) {
        return registrationRepository.findLive(email, LocalDateTime.now());
    }

    /**
     * Spends the token and answers what was asked for. Expired, spent and never-existed read the
     * same from outside: the difference is only of interest to whoever is guessing.
     */
    @Transactional
    public PendingRegistration consume(String token) {
        LocalDateTime now = LocalDateTime.now();
        String hash = CryptoUtils.sha256Hex(token);

        PendingRegistration pending = registrationRepository.findByTokenHash(hash)
                .filter(candidate -> candidate.getUsedAt() == null)
                .filter(candidate -> candidate.getExpiresAt().isAfter(now))
                .orElseThrow(() -> new ForbiddenStatusException("The link is invalid or has expired"));

        if (registrationRepository.claim(hash, now) == 0) {
            // Two clicks on one letter must create one account, not two.
            log.warn("concurrent confirmation of one registration");
            throw new ForbiddenStatusException("The link is invalid or has expired");
        }

        return pending;
    }

    /**
     * Whether another letter may go to this address now. Counted by address rather than by person
     * because there is no person yet — which is why it matters more here than anywhere else: this
     * endpoint needs no account at all, so without a limit anyone's mailbox can be filled from it.
     */
    public boolean allowedToSend(String email) {
        LocalDateTime now = LocalDateTime.now();

        if (registrationRepository.countIssuedSince(email, now.minus(MAIL_INTERVAL)) > 0) {
            return false;
        }
        return registrationRepository.countIssuedSince(email, now.minus(MAIL_WINDOW)) < MAIL_LIMIT;
    }
}
