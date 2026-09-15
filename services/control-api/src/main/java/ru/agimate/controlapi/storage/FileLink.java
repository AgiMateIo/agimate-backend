package ru.agimate.controlapi.storage;

import ru.agimate.controlapi.database.entities.StoredFile;

import java.util.UUID;

/**
 * What issuing a content link needs to know about one version of a file: the blob key is built from
 * the owner, the id and the version, the response headers of a direct link — from the mime and the
 * name. Deliberately not {@link StoredFile}: webchat issues links from the stored parts of a message,
 * without reading the file row (docs/connectors/files.md).
 *
 * @param name    the file name when the producer knew one; {@code null} where there is none in nature
 * @param version the version the link addresses; a stored part that predates versions is version 1
 */
public record FileLink(UUID userId, String fileId, String mime, String name, int version) {

    public static final int FIRST_VERSION = 1;

    /** The current version of the file. */
    public static FileLink of(StoredFile file) {
        return new FileLink(file.getUserId(), FileIds.external(file.getId()), file.getMime(), file.getName(),
                file.getVersion());
    }

    /**
     * Key of the contents in the {@link BlobStore} — the single place the layout is defined. Version 1
     * keeps the key files had before versions, so no object moves; later versions sit next to it
     * rather than under it, because {@link LocalBlobStore} maps keys straight onto paths and
     * {@code agf_…} cannot be a file and a directory at once.
     */
    public String blobKey() {
        return blobKey(userId, fileId, version);
    }

    public static String blobKey(UUID userId, String fileId, int version) {
        String base = userId + "/" + fileId;
        return version <= FIRST_VERSION ? base : base + ".v" + version;
    }
}
