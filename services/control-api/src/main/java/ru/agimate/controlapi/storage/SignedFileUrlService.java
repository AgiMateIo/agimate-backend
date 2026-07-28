package ru.agimate.controlapi.storage;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.config.FileStorageProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Signed file links for the browser (docs/connectors/files.md): an {@code Authorization} header
 * cannot be put into {@code <img src>}, so access is authorised by a capability link — HMAC-SHA256
 * over {@code fileId|exp} with a short TTL. Ownership is checked when the link is issued (parts are
 * handed only to the owner of the webchat session); until {@code exp} the link itself is equivalent
 * to the right to read one file.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignedFileUrlService {

    public static final String PATH_PREFIX = "/files/";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final FileStorageProperties props;

    private SecretKeySpec key;

    @PostConstruct
    void initKey() {
        String secret = props.getUrlSecret();
        if (secret == null || secret.isBlank()) {
            // Outside the dev profiles this is unreachable: the secret is mandatory (SecurityGuardConfig,
            // fail-fast). In dev a random per-boot key gives working links with no configuration; after a restart
            // the issued links expire — the frontend re-reads the history and gets fresh ones.
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            key = new SecretKeySpec(random, HMAC_ALGORITHM);
            log.warn("app.files.url-secret is not set - using a random per-boot key (dev only)");
            return;
        }
        key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    /** A relative signed URL ({@code /files/agf_…?exp=…&sig=…}); the origin is added by the frontend. */
    public String issue(String fileId) {
        long exp = Instant.now().plus(props.getUrlTtl()).getEpochSecond();
        return PATH_PREFIX + fileId + "?exp=" + exp + "&sig=" + sign(fileId, exp);
    }

    /** Whether the signature is valid and unexpired; the reasons for refusal are deliberately indistinguishable to the client. */
    public boolean verify(String fileId, long exp, String sig) {
        if (sig == null || Instant.now().getEpochSecond() > exp) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(fileId, exp).getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String fileId, long exp) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal((fileId + "|" + exp).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }
}
