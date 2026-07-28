package ru.agimate.controlapi.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.config.FileStorageProperties;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A {@link BlobStore} on the local disk — the default backend ({@code app.files.backend=local}): for
 * development and single-node runs without S3/MinIO. The key {@code userId/agf_<id>} maps onto a
 * directory tree under {@code app.files.local-dir} (empty — {@code ~/.agimate/files}). Writing goes
 * through a temporary file with an atomic move: a half-written blob is never visible to readers.
 *
 * <p>Keys are generated only by {@link FileStorageService} (UUIDs), yet every resolution is still
 * checked for escaping the root (belt-and-braces against path traversal).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.files", name = "backend", havingValue = "local", matchIfMissing = true)
public class LocalBlobStore implements BlobStore {

    private final Path root;

    public LocalBlobStore(FileStorageProperties props) {
        String dir = props.getLocalDir();
        this.root = (dir == null || dir.isBlank()
                ? Path.of(System.getProperty("user.home"), ".agimate", "files")
                : Path.of(dir)).toAbsolutePath().normalize();
        log.info("local blob store root: {}", root);
    }

    @Override
    public void put(String key, InputStream content, long contentLength, String mime) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                Files.copy(content, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new FileStorageException("local blob store put failed: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream get(String key) {
        Path target = resolve(key);
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new FileStorageException("blob not found: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Path target = resolve(key);
            Files.deleteIfExists(target);
            // We do not clean up an empty user directory: racing a concurrent put costs more than the litter.
        } catch (IOException e) {
            throw new FileStorageException("local blob store delete failed: " + e.getMessage(), e);
        }
    }

    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new FileStorageException("invalid blob key: " + key);
        }
        return target;
    }
}
