package ru.agimate.controlapi.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.agimate.controlapi.config.FileStorageProperties;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LocalBlobStore")
class LocalBlobStoreTest {

    private static final BlobStore.ResponseHeaders HEADERS =
            new BlobStore.ResponseHeaders("text/plain", "attachment; filename=\"notes.txt\"");

    @TempDir
    Path tempDir;

    private LocalBlobStore store;

    @BeforeEach
    void setUp() {
        FileStorageProperties props = new FileStorageProperties();
        props.setLocalDir(tempDir.toString());
        store = new LocalBlobStore(props);
    }

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("put/get roundtrip с вложенным ключом userId/agf_id")
    void roundtrip() throws Exception {
        String key = UUID.randomUUID() + "/agf_" + UUID.randomUUID();
        store.put(key, bytes("hello"), 5, HEADERS);

        try (InputStream in = store.get(key)) {
            assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertTrue(Files.exists(tempDir.resolve(key)));
        // временных файлов не осталось
        try (var files = Files.list(tempDir.resolve(key).getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    @DisplayName("get отсутствующего ключа → FileStorageException")
    void getMissing() {
        assertThrows(FileStorageException.class, () -> store.get("nope/agf_missing"));
    }

    @Test
    @DisplayName("delete идемпотентен")
    void deleteIdempotent() {
        String key = "u1/agf_x";
        store.put(key, bytes("data"), 4, HEADERS);
        store.delete(key);
        store.delete(key);
        assertThrows(FileStorageException.class, () -> store.get(key));
    }

    @Test
    @DisplayName("ключ с выходом за корень отвергается")
    void rejectsTraversal() {
        assertThrows(FileStorageException.class, () -> store.get("../outside"));
        assertThrows(FileStorageException.class,
                () -> store.put("../../etc/passwd", bytes("x"), 1, HEADERS));
    }
}
