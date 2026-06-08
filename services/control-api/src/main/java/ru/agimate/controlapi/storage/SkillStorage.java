package ru.agimate.controlapi.storage;

import java.io.InputStream;
import java.util.List;

/**
 * Abstraction for skill file storage.
 * Implementations can use local filesystem, S3, GCS, etc.
 */
public interface SkillStorage {

    void saveFile(String basePath, String relativePath, InputStream content, long size);

    InputStream readFile(String basePath, String relativePath);

    void deleteFile(String basePath, String relativePath);

    void deleteAll(String basePath);

    List<FileEntry> listFiles(String basePath);

    boolean exists(String basePath, String relativePath);

    int countFiles(String basePath);

    void copyAll(String sourceBasePath, String targetBasePath);

    /**
     * Returns the last modified time of the file in epoch milliseconds.
     * Returns -1 if the file does not exist.
     */
    long lastModified(String basePath, String relativePath);

    record FileEntry(String path, String name, long size, boolean directory) {}
}
