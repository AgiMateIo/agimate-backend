package ru.agimate.controlapi.storage;

/**
 * Файл не резолвится для вызывающего: неизвестный/чужой/просроченный/незавершённый fileId —
 * причины намеренно неразличимы (не раскрываем существование чужих файлов). На HTTP это 404.
 */
public class StoredFileNotFoundException extends FileStorageException {

    public StoredFileNotFoundException(String fileId) {
        super("file not found: " + fileId);
    }
}
