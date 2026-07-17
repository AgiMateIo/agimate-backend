package ru.agimate.controlapi.storage;

/**
 * Доменная ошибка файлового слоя (квота, размер, неизвестный/чужой fileId, недоступный backend).
 * На границах переупаковывается: в коннекторном слое → {@code ConnectorException}, на HTTP —
 * в {@code *StatusException}.
 */
public class FileStorageException extends RuntimeException {

    public FileStorageException(String message) {
        super(message);
    }

    public FileStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
