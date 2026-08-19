package ru.agimate.userapi.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.userapi.config.S2sProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Verifies the key another service of ours presents on {@code /internal/**}. */
@Slf4j
@Service
@RequiredArgsConstructor
public class S2sKeyAuthService {

    /** Below this a shared secret is a password, not a key, and the surface it opens is not one to guess at. */
    private static final int MIN_KEY_LENGTH = 32;

    private final S2sProperties properties;

    /**
     * A key too short to be one fails the boot rather than the first call: a surface that answers 401
     * to its only legitimate caller looks like a network problem, and nobody goes looking in the
     * config for it.
     */
    @PostConstruct
    void checkConfiguration() {
        String key = properties.getKey();
        if (key == null || key.isBlank()) {
            log.info("app.s2s.key is not set — the internal API accepts nobody");
            return;
        }
        if (key.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException(
                    "app.s2s.key is shorter than " + MIN_KEY_LENGTH + " characters — generate one with "
                            + "`openssl rand -hex 32`");
        }
    }

    /**
     * Constant-time comparison: {@code equals} returns as soon as two bytes differ, and the timing of
     * that is enough to recover a secret one character at a time.
     */
    public boolean isValid(String presented) {
        String configured = properties.getKey();
        if (configured == null || configured.isBlank() || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                configured.getBytes(StandardCharsets.UTF_8));
    }
}
