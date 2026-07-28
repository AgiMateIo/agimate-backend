package ru.agimate.controlapi.storage;

import java.io.InputStream;

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
}
