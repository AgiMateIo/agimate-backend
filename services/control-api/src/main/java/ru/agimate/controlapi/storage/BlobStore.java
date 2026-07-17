package ru.agimate.controlapi.storage;

import java.io.InputStream;

/**
 * Байтовое хранилище файлового слоя (docs/connectors/files.md). Ключи назначает
 * {@link FileStorageService}; метаданных и владения на этом уровне нет — только блобы.
 */
public interface BlobStore {

    void put(String key, InputStream content, long contentLength, String mime);

    /** Стрим содержимого; закрывает вызывающий. */
    InputStream get(String key);

    /** Идемпотентно: удаление отсутствующего ключа — не ошибка. */
    void delete(String key);
}
