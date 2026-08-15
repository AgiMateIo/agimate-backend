package ru.agimate.userapi.service.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.util.CryptoUtils;
import ru.agimate.userapi.database.entities.AuthClient;
import ru.agimate.userapi.database.entities.AuthCode;
import ru.agimate.userapi.database.entities.SessionRevokeReason;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.database.repositories.AuthCodeRepository;
import ru.agimate.userapi.service.UserService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The exchange an installed application goes through instead of receiving a cookie: the login ends
 * with a one-time code in the redirect, and the code is worth tokens only to whoever can produce
 * the verifier behind the challenge that started the login.
 *
 * <p>This is authorization code flow rebuilt in miniature for a single first-party client, which is
 * a thing worth doing carefully rather than approximately — hence a hashed code, a lifetime in
 * seconds, and a second exchange that revokes rather than repeats. See docs/decisions/native-auth.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NativeAuthService {

    /** Long enough for the redirect to reach the app, short enough to be useless if it is captured. */
    private static final int CODE_TTL_SECONDS = 60;

    /** 256 bits — the code is the whole secret between the redirect and the exchange. */
    private static final int CODE_BYTES = 32;

    /** RFC 7636 §4.1. */
    private static final Pattern CODE_VERIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9\\-._~]{43,128}$");

    private final AuthCodeRepository codeRepository;
    private final AuthSessionService sessionService;
    private final UserService userService;

    /** @return the code itself, which exists here and in the redirect and nowhere else afterwards */
    @Transactional
    public String issueCode(UUID userId, String codeChallenge, String redirectUri) {
        String code = CryptoUtils.randomHex(CODE_BYTES);

        AuthCode authCode = new AuthCode();
        authCode.setCodeHash(CryptoUtils.sha256Hex(code));
        authCode.setUserId(userId);
        authCode.setCodeChallenge(codeChallenge);
        authCode.setRedirectUri(redirectUri);
        authCode.setExpiresAt(LocalDateTime.now().plusSeconds(CODE_TTL_SECONDS));
        codeRepository.save(authCode);

        return code;
    }

    @Transactional(noRollbackFor = ForbiddenStatusException.class)
    public IssuedTokens exchange(String code, String codeVerifier, String redirectUri, String deviceLabel) {
        if (!CODE_VERIFIER_PATTERN.matcher(codeVerifier).matches()) {
            throw new BadRequestStatusException("code_verifier is malformed");
        }

        LocalDateTime now = LocalDateTime.now();
        AuthCode authCode = codeRepository.findByCodeHash(CryptoUtils.sha256Hex(code))
                .orElseThrow(() -> new ForbiddenStatusException("Invalid authorization code"));

        if (authCode.getUsedAt() != null) {
            // A code offered twice means it was seen by someone it was not issued to — the tokens
            // the first exchange produced are no longer only in the app's hands.
            log.warn("authorization code replayed for user {}", authCode.getUserId());
            if (authCode.getSessionId() != null) {
                sessionService.revoke(authCode.getSessionId(), SessionRevokeReason.REPLAY);
            }
            throw new ForbiddenStatusException("Authorization code was already used");
        }
        if (authCode.getExpiresAt().isBefore(now)) {
            throw new ForbiddenStatusException("Authorization code expired");
        }
        if (!authCode.getRedirectUri().equals(redirectUri)) {
            throw new ForbiddenStatusException("redirect_uri does not match the one the login started with");
        }
        if (!matchesChallenge(codeVerifier, authCode.getCodeChallenge())) {
            throw new ForbiddenStatusException("PKCE verification failed");
        }

        UUID codeId = authCode.getId();
        UUID userId = authCode.getUserId();

        if (codeRepository.claim(authCode.getCodeHash(), now) == 0) {
            // Two exchanges of the same code arrived together. Whoever lost cannot yet see the
            // session the winner is still creating, so there is nothing to revoke from here; a later
            // attempt takes the branch above, which can.
            log.warn("concurrent exchange of one authorization code for user {}", userId);
            throw new ForbiddenStatusException("Authorization code was already used");
        }

        UserEntity user = userService.findById(userId)
                .orElseThrow(() -> new ForbiddenStatusException("User no longer exists"));

        IssuedTokens tokens = sessionService.open(user, AuthClient.NATIVE, deviceLabel);
        codeRepository.attachSession(codeId, tokens.sessionId(), now);

        return tokens;
    }

    /** S256 only: {@code BASE64URL(SHA256(ASCII(code_verifier)))} against the stored challenge. */
    private boolean matchesChallenge(String codeVerifier, String codeChallenge) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.US_ASCII),
                    codeChallenge.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
