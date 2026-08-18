package ru.agimate.controlapi.storage;

import ru.agimate.controlapi.database.entities.StoredFile;

import java.util.UUID;

/**
 * What issuing a content link needs to know about a file: the blob key is built from the owner and
 * the id, the response headers of a direct link — from the mime and the name. Deliberately not
 * {@link StoredFile}: webchat issues links from the stored parts of a message, without reading the
 * file row (docs/connectors/files.md).
 *
 * @param name the file name when the producer knew one; {@code null} where there is none in nature
 */
public record FileLink(UUID userId, String fileId, String mime, String name) {

    public static FileLink of(StoredFile file) {
        return new FileLink(file.getUserId(), FileIds.external(file.getId()), file.getMime(), file.getName());
    }

    /** Key of the contents in the {@link BlobStore} — the single place the layout is defined. */
    public String blobKey() {
        return userId + "/" + fileId;
    }
}
