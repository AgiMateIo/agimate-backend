package ru.agimate.controlapi.storage;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.config.FileStorageProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Signed file links for the browser (docs/connectors/files.md): an {@code Authorization} header
 * cannot be put into {@code <img src>}, so access is authorised by a capability link with a short TTL.
 * Ownership is checked when the link is issued (parts are handed only to the owner of the webchat
 * session); until it expires the link itself is equivalent to the right to read one file.
 *
 * <p>Two shapes of that link, in this order:
 * <ol>
 *   <li>a presigned URL straight into the object store, when the backend issues one
 *       ({@code app.files.presign}) — the bytes then never pass through control-api, and the browser
 *       gets range requests and resumable downloads for free;</li>
 *   <li>otherwise a relative {@code /files/agf_…?exp&sig} served by us, authenticated by HMAC-SHA256
 *       over {@code fileId|exp}.</li>
 * </ol>
 * The two differ in revocation: the HMAC link goes through the {@code files} row on every download and
 * dies the moment the file expires, a presigned one is answered by the storage alone and lives until
 * the blob is swept. See {@link BlobStore#presignGet}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignedFileUrlService {

    public static final String PATH_PREFIX = "/files/";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final FileStorageProperties props;
    private final BlobStore blobStore;

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

    /**
     * A link to the contents: absolute when the object store signed it itself, otherwise the relative
     * {@code /files/agf_…?exp=…&sig=…} whose origin the frontend adds.
     */
    public String issue(FileLink link) {
        Optional<URI> direct = presign(link);
        if (direct.isPresent()) {
            return direct.get().toString();
        }
        long exp = Instant.now().plus(props.getUrlTtl()).getEpochSecond();
        return PATH_PREFIX + link.fileId() + "?exp=" + exp + "&sig=" + sign(link.fileId(), exp);
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

    /**
     * A direct link plus the presentation the streaming path would have applied. A link with no mime
     * is not signed at all: the response headers would then be built from a guess, and an image would
     * reach the browser as a download — our own path reads the real mime from the {@code files} row.
     */
    private Optional<URI> presign(FileLink link) {
        if (link.mime() == null) {
            return Optional.empty();
        }
        return blobStore.presignGet(link.blobKey(), props.getUrlTtl(),
                FileContentHeaders.forDelivery(link, true));
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
