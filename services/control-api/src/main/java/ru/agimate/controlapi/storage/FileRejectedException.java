package ru.agimate.controlapi.storage;

/** Файл отвергнут по правилам слоя (размер, суточная квота, невалидный вход) — на HTTP это 400. */
public class FileRejectedException extends FileStorageException {

    public FileRejectedException(String message) {
        super(message);
    }
}
