package ru.agimate.controlapi.storage;

/**
 * A new version was refused: another call is writing the file right now, or it changed after the
 * caller read it. Writes of one file go one at a time and the loser is not retried — the caller
 * re-reads and decides again.
 */
public class FileVersionConflictException extends FileStorageException {

    public FileVersionConflictException(String fileId) {
        super("file " + fileId + " is being written by another call or has changed since it was read; "
                + "read it again and retry");
    }
}
