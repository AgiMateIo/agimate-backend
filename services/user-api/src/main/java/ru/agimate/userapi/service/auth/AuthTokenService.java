package ru.agimate.userapi.service.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.util.CryptoUtils;
import ru.agimate.userapi.database.entities.AuthToken;
import ru.agimate.userapi.database.entities.AuthTokenPurpose;
import ru.agimate.userapi.database.repositories.AuthTokenRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One-time secrets that travel by mail, and the rules that make them worth trusting: hashed at rest,
 * spent once, short-lived, and rationed.
 *
 * <p>The purpose is checked on the way out as well as the way in, so that a token issued for one
 * thing cannot be presented for another — the discriminator is a security boundary, not a label.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthTokenService {

    /** 256 bits: between the letter and the exchange, the token is the whole secret. */
    private static final int TOKEN_BYTES = 32;

    private final AuthTokenRepository tokenRepository;

    /**
     * @return the token itself, which from here on exists only in the letter and in the mailbox it
     *         is delivered to
     */
    @Transactional
    public String issue(UUID userId, AuthTokenPurpose purpose, Duration ttl) {
        LocalDateTime now = LocalDateTime.now();
        // Live tokens are deliberately left alone. Retiring them read well — only the newest link
        // works — until you notice who else can trigger an issue: anybody, from an endpoint that
        // needs no account, killing a link the owner is holding. Several letters into one mailbox
        // over an hour prove the same thing anyway, and the TTL still bounds them.
        String token = CryptoUtils.randomHex(TOKEN_BYTES);

        AuthToken authToken = new AuthToken();
        authToken.setPurpose(purpose);
        authToken.setUserId(userId);
        authToken.setTokenHash(CryptoUtils.sha256Hex(token));
        authToken.setExpiresAt(now.plus(ttl));
        tokenRepository.save(authToken);

        return token;
    }

    /**
     * Spends the token and answers whose account it opens. Every refusal reads the same from the
     * outside: an expired token, a spent one and one that never existed are all "invalid", because
     * the difference is only of interest to whoever is guessing.
     */
    @Transactional
    public UUID consume(String token, AuthTokenPurpose purpose) {
        LocalDateTime now = LocalDateTime.now();
        String hash = CryptoUtils.sha256Hex(token);

        AuthToken authToken = tokenRepository.findByTokenHash(hash)
                .filter(candidate -> candidate.getPurpose() == purpose)
                .filter(candidate -> candidate.getUsedAt() == null)
                .filter(candidate -> candidate.getExpiresAt().isAfter(now))
                .orElseThrow(() -> new ForbiddenStatusException("The link is invalid or has expired"));

        if (tokenRepository.claim(hash, now) == 0) {
            // Two clicks arrived together; the loser is refused rather than served a second time.
            log.warn("concurrent use of one {} token for user {}", purpose, authToken.getUserId());
            throw new ForbiddenStatusException("The link is invalid or has expired");
        }

        return authToken.getUserId();
    }

    /**
     * Whether another letter of this purpose may be sent to this person now. Counted in the database
     * rather than in memory: a limit a restart resets is not a limit, and this one is what stands
     * between somebody's mailbox and being used as a weapon against them.
     *
     * @param interval no two letters closer together than this
     * @param window   and no more than {@code limit} of them within this
     */
    public boolean allowedToSend(UUID userId, AuthTokenPurpose purpose,
                                 Duration interval, Duration window, int limit) {
        LocalDateTime now = LocalDateTime.now();

        if (tokenRepository.countIssuedSince(userId, purpose, now.minus(interval)) > 0) {
            return false;
        }
        return tokenRepository.countIssuedSince(userId, purpose, now.minus(window)) < limit;
    }
}
