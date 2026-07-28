package ru.agimate.controlapi.storage;

/**
 * The file does not resolve for the caller: an unknown, foreign, expired or unfinished fileId — the
 * reasons are deliberately indistinguishable (we do not reveal the existence of other people's
 * files). 404 over HTTP.
 */
public class StoredFileNotFoundException extends FileStorageException {

    public StoredFileNotFoundException(String fileId) {
        super("file not found: " + fileId);
    }
}
