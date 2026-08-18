package ru.agimate.controlapi.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Byte storage of the file layer (docs/connectors/files.md). Keys are assigned by
 * {@link FileStorageService}; there is no metadata or ownership at this level — blobs only.
 */
public interface BlobStore {

    void put(String key, InputStream content, long contentLength, String mime);

    /** Stream of the contents; the caller closes it. */
    InputStream get(String key);

    /** Idempotent: deleting an absent key is not an error. */
    void delete(String key);

    /**
     * A link the browser may follow straight to the storage, valid for {@code ttl}. {@code empty} —
     * this backend issues none (the local disk) or is not configured to, and the caller falls back to
     * serving the bytes itself through {@code GET /files/…}.
     *
     * <p>Such a link is a capability outside our reach: unlike {@code /files/…} it does not pass
     * through the {@code files} row, so it survives deletion of the file until the blob itself is
     * swept. Hence the short TTL of {@code app.files.url-ttl}.
     *
     * @param headers what the response should claim the contents are; a backend that cannot override
     *                response headers serves what was stored at upload time
     */
    default Optional<URI> presignGet(String key, Duration ttl, ResponseHeaders headers) {
        return Optional.empty();
    }

    /** Values for {@code Content-Type} / {@code Content-Disposition} of a presigned link ({@link FileContentHeaders}). */
    record ResponseHeaders(String contentType, String contentDisposition) {}
}
