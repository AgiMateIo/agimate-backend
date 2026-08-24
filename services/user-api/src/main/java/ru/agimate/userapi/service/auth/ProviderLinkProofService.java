package ru.agimate.userapi.service.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.util.CryptoUtils;
import ru.agimate.userapi.database.entities.OAuthProviderType;
import ru.agimate.userapi.database.entities.ProviderLinkProof;
import ru.agimate.userapi.database.repositories.ProviderLinkProofRepository;
import ru.agimate.userapi.security.oauth2.providers.OAuthUserInfo;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * What a provider vouched for, held for the few minutes between the redirect and the request that
 * claims it. Same rules as every other one-time secret in this service — hashed at rest, spent once,
 * short-lived — for the same reason: between the two it is the whole secret.
 *
 * <p>Apart from {@link ProviderIdentityService} the way {@link AuthTokenService} is apart from
 * {@link PasswordAuthService}: it owns one table and its rules, and nothing else.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProviderLinkProofService {

    /**
     * Long enough for a redirect to land and a page to send one request, and no longer: unlike a
     * letter, nobody reads this in the morning.
     */
    private static final Duration PROOF_TTL = Duration.ofMinutes(5);

    /** 256 bits, and the shape the redeeming endpoint checks before it goes near the database. */
    private static final int PROOF_BYTES = 32;

    private static final Pattern PROOF_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private final ProviderLinkProofRepository proofRepository;

    public static boolean isValidProof(String value) {
        return value != null && PROOF_PATTERN.matcher(value).matches();
    }

    /** @return the proof itself, which from here on exists in the redirect and in nothing else */
    @Transactional
    public String issue(OAuthProviderType provider, OAuthUserInfo userInfo) {
        String proof = CryptoUtils.randomHex(PROOF_BYTES);

        ProviderLinkProof row = new ProviderLinkProof();
        row.setProofHash(CryptoUtils.sha256Hex(proof));
        row.setOauthProvider(provider);
        row.setProviderUserId(userInfo.providerUserId());
        row.setEmail(userInfo.email());
        row.setFirstName(userInfo.firstName());
        row.setLastName(userInfo.lastName());
        row.setExpiresAt(LocalDateTime.now().plus(PROOF_TTL));
        proofRepository.save(row);

        return proof;
    }

    /**
     * Spends the proof and answers what the provider said. Expired, spent and never-existed read the
     * same from outside: the difference is only of interest to whoever is guessing.
     */
    @Transactional
    public ProviderLinkProof consume(String proof) {
        if (!isValidProof(proof)) {
            throw new ForbiddenStatusException("The linking attempt is invalid or has expired");
        }

        LocalDateTime now = LocalDateTime.now();
        String hash = CryptoUtils.sha256Hex(proof);

        ProviderLinkProof row = proofRepository.findByProofHash(hash)
                .filter(candidate -> candidate.getUsedAt() == null)
                .filter(candidate -> candidate.getExpiresAt().isAfter(now))
                .orElseThrow(() -> new ForbiddenStatusException(
                        "The linking attempt is invalid or has expired"));

        if (proofRepository.claim(hash, now) == 0) {
            log.warn("concurrent use of one link proof for {}", row.getOauthProvider());
            throw new ForbiddenStatusException("The linking attempt is invalid or has expired");
        }

        return row;
    }
}
