package ru.agimate.controlapi.storage;

/**
 * A domain error of the file layer (quota, size, unknown or foreign fileId, unreachable backend).
 * Repacked at the boundaries: into {@code ConnectorException} in the connector layer, into a
 * {@code *StatusException} over HTTP.
 */
public class FileStorageException extends RuntimeException {

    public FileStorageException(String message) {
        super(message);
    }

    public FileStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
