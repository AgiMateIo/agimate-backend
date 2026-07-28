package ru.agimate.controlapi.storage;

/** The file was rejected by the layer's rules (size, daily quota, invalid input) — 400 over HTTP. */
public class FileRejectedException extends FileStorageException {

    public FileRejectedException(String message) {
        super(message);
    }
}
