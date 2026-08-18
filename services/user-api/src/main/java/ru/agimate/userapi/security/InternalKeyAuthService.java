package ru.agimate.userapi.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.userapi.config.InternalApiProperties;
import ru.agimate.common.security.keys.AppKeyUtils;
import ru.agimate.common.security.keys.ParsedAuthkey;
import ru.agimate.common.security.keys.ParsedAppKey;

/**
 * Verifies the key another service of ours presents on {@code /internal/**}. The same shape as the
 * worker pool key — the full key travels, the hash sits in the config — and the same reason: these
 * keys have no row to be verified against.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InternalKeyAuthService {

    public static final String INTERNAL_KEY_PREFIX = "intr";

    private final InternalApiProperties properties;

    private ParsedAuthkey configured;

    /**
     * A malformed authkey fails the boot rather than the first call: the alternative is an internal
     * surface that answers 401 to its only legitimate caller and looks like a network problem.
     */
    @PostConstruct
    void init() {
        String authkey = properties.getAuthkey();
        if (authkey == null || authkey.isBlank()) {
            log.info("app.internal.authkey is not set — the internal API accepts nobody");
            return;
        }
        ParsedAuthkey parsed = ParsedAuthkey.parse(authkey);
        if (!INTERNAL_KEY_PREFIX.equals(parsed.prefix())) {
            throw new IllegalStateException("app.internal.authkey has wrong prefix '" + parsed.prefix()
                    + "', expected '" + INTERNAL_KEY_PREFIX + "'");
        }
        this.configured = parsed;
    }

    public boolean isValid(String fullKey) {
        if (configured == null || fullKey == null || fullKey.isBlank()) {
            return false;
        }

        ParsedAppKey parsedKey;
        try {
            parsedKey = AppKeyUtils.parse(fullKey);
        } catch (IllegalArgumentException e) {
            log.debug("Invalid internal key format: {}", e.getMessage());
            return false;
        }

        return INTERNAL_KEY_PREFIX.equals(parsedKey.prefix())
                && AppKeyUtils.verifyChecksum(parsedKey)
                && configured.keyId().equals(parsedKey.keyId())
                && AppKeyUtils.verifySecret(parsedKey.secret(), configured.keyHash());
    }
}
