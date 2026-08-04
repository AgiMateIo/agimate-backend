package ru.agimate.controlapi.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.config.FileStorageProperties;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.enums.FileStatus;
import ru.agimate.controlapi.database.repositories.StoredFileRepository;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FileStorageService")
class FileStorageServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    /** SHA-256("hello") */
    private static final String HELLO_SHA256 =
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @Mock
    private StoredFileRepository repository;

    /** In-memory BlobStore: put читает стрим (иначе не посчитается SHA-256), delete идемпотентен. */
    private static class InMemoryBlobStore implements BlobStore {
        final Map<String, byte[]> blobs = new HashMap<>();

        @Override
        public void put(String key, InputStream content, long contentLength, String mime) {
            try {
                blobs.put(key, content.readAllBytes());
            } catch (Exception e) {
                throw new FileStorageException("put failed", e);
            }
        }

        @Override
        public InputStream get(String key) {
            byte[] data = blobs.get(key);
            if (data == null) {
                throw new FileStorageException("blob not found: " + key);
            }
            return new ByteArrayInputStream(data);
        }

        @Override
        public void delete(String key) {
            blobs.remove(key);
        }
    }

    private InMemoryBlobStore blobStore;
    private FileStorageProperties props;
    private FileStorageService service;

    @BeforeEach
    void setUp() {
        blobStore = new InMemoryBlobStore();
        props = new FileStorageProperties();
        service = new FileStorageService(repository, blobStore, props);
    }

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static NewFile spec(UUID userId, String origin, String mime, long sizeBytes) {
        return NewFile.builder().userId(userId).origin(origin).mime(mime).sizeBytes(sizeBytes).build();
    }

    @Nested
    @DisplayName("store")
    class Store {

        @Test
        @DisplayName("happy path: блоб записан, SHA-256 посчитан, статус READY")
        void storesAndComputesSha() {
            when(repository.sumBytesSince(eq(USER_ID), any())).thenReturn(0L);

            StoredFile file = service.store(spec(USER_ID, "app/take_screenshot", "text/plain", 5), bytes("hello"));

            assertEquals(FileStatus.READY, file.getStatus());
            assertEquals(HELLO_SHA256, file.getSha256());
            assertEquals(5L, file.getSizeBytes());
            assertNotNull(file.getId());
            assertTrue(file.getExpiresAt().isAfter(LocalDateTime.now()));
            String key = USER_ID + "/" + FileIds.external(file.getId());
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), blobStore.blobs.get(key));
            // Две записи строки: UPLOADING до аплоада и READY после.
            verify(repository, org.mockito.Mockito.times(2)).save(file);
        }

        @Test
        @DisplayName("файл больше лимита — отказ до записи")
        void rejectsOversize() {
            props.setMaxFileSizeBytes(4);
            assertThrows(FileStorageException.class,
                    () -> service.store(spec(USER_ID, "t", "text/plain", 5), bytes("hello")));
            assertTrue(blobStore.blobs.isEmpty());
        }

        @Test
        @DisplayName("превышение суточной квоты — отказ")
        void rejectsWhenQuotaExceeded() {
            props.setUserDailyBytes(100);
            when(repository.sumBytesSince(eq(USER_ID), any())).thenReturn(98L);
            assertThrows(FileStorageException.class,
                    () -> service.store(spec(USER_ID, "t", "text/plain", 5), bytes("hello")));
            assertTrue(blobStore.blobs.isEmpty());
        }

        @Test
        @DisplayName("неположительный размер — отказ")
        void rejectsNonPositiveSize() {
            assertThrows(FileStorageException.class,
                    () -> service.store(spec(USER_ID, "t", "text/plain", 0), bytes("")));
        }

        @Test
        @DisplayName("имя и агент-производитель сохраняются в строке")
        void storesNameAndProducingAgent() {
            when(repository.sumBytesSince(eq(USER_ID), any())).thenReturn(0L);
            UUID agentId = UUID.randomUUID();

            StoredFile file = service.store(NewFile.builder()
                    .userId(USER_ID).agentId(agentId).origin("sheets:export")
                    .name("отчёт.csv").mime("text/csv").sizeBytes(5).build(), bytes("hello"));

            assertEquals("отчёт.csv", file.getName());
            assertEquals(agentId, file.getAgentId());
        }

        @Test
        @DisplayName("агент-производитель неизвестен — строка пишется без него")
        void storesWithoutAgent() {
            when(repository.sumBytesSince(eq(USER_ID), any())).thenReturn(0L);

            StoredFile file = service.store(spec(USER_ID, "webchat", "text/plain", 5), bytes("hello"));

            assertNull(file.getAgentId());
            assertNull(file.getName());
        }
    }

    @Nested
    @DisplayName("имя файла")
    class Name {

        @Test
        @DisplayName("путь в имени срезается до последнего сегмента")
        void stripsPath() {
            assertEquals("shot.png", named("C:\\Users\\me\\shot.png").name());
            assertEquals("shot.png", named("../../etc/shot.png").name());
        }

        @Test
        @DisplayName("кавычки и управляющие символы вырезаются — иначе они уедут в заголовок")
        void stripsHeaderBreakers() {
            assertEquals("report.pdf", named("re\"po\rrt\n.pdf").name().replace(" ", ""));
        }

        @Test
        @DisplayName("пустое, пробельное и «..» — имени нет")
        void blankBecomesNull() {
            assertNull(named("   ").name());
            assertNull(named("..").name());
            assertNull(named(null).name());
        }

        @Test
        @DisplayName("слишком длинное имя обрезается")
        void truncatesLongName() {
            assertEquals(255, named("a".repeat(300)).name().length());
        }

        private NewFile named(String name) {
            return NewFile.builder().userId(USER_ID).name(name).mime("text/plain").sizeBytes(1).build();
        }
    }

    @Nested
    @DisplayName("open")
    class Open {

        private StoredFile readyFile(UUID owner) {
            when(repository.sumBytesSince(eq(owner), any())).thenReturn(0L);
            return service.store(spec(owner, "t", "text/plain", 5), bytes("hello"));
        }

        @Test
        @DisplayName("владелец читает своё содержимое")
        void opensOwnFile() throws Exception {
            StoredFile file = readyFile(USER_ID);
            when(repository.findById(file.getId())).thenReturn(Optional.of(file));

            FileStorageService.FileContent content =
                    service.open(USER_ID, FileIds.external(file.getId()));

            assertEquals("hello", new String(content.content().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(file.getId(), content.file().getId());
        }

        @Test
        @DisplayName("чужой fileId не резолвится")
        void rejectsForeignFile() {
            StoredFile file = readyFile(USER_ID);
            when(repository.findById(file.getId())).thenReturn(Optional.of(file));
            assertThrows(FileStorageException.class,
                    () -> service.open(UUID.randomUUID(), FileIds.external(file.getId())));
        }

        @Test
        @DisplayName("просроченный файл не резолвится")
        void rejectsExpiredFile() {
            StoredFile file = readyFile(USER_ID);
            file.setExpiresAt(LocalDateTime.now().minusMinutes(1));
            when(repository.findById(file.getId())).thenReturn(Optional.of(file));
            assertThrows(FileStorageException.class,
                    () -> service.open(USER_ID, FileIds.external(file.getId())));
        }

        @Test
        @DisplayName("UPLOADING-файл не резолвится")
        void rejectsUploadingFile() {
            StoredFile file = readyFile(USER_ID);
            file.setStatus(FileStatus.UPLOADING);
            when(repository.findById(file.getId())).thenReturn(Optional.of(file));
            assertThrows(FileStorageException.class,
                    () -> service.open(USER_ID, FileIds.external(file.getId())));
        }

        @Test
        @DisplayName("не-fileId строка — отказ без похода в БД")
        void rejectsMalformedId() {
            assertThrows(FileStorageException.class, () -> service.open(USER_ID, "not-a-file-id"));
        }
    }

    @Nested
    @DisplayName("purgeExpiredBatch")
    class Purge {

        @Test
        @DisplayName("удаляет блоб и строку для каждого файла из батча")
        void purgesBlobAndRow() {
            when(repository.sumBytesSince(eq(USER_ID), any())).thenReturn(0L);
            StoredFile file = service.store(spec(USER_ID, "t", "text/plain", 5), bytes("hello"));
            when(repository.claimPurgeBatch(anyInt())).thenReturn(List.of(file));

            int purged = service.purgeExpiredBatch(100);

            assertEquals(1, purged);
            assertTrue(blobStore.blobs.isEmpty());
            verify(repository).delete(file);
        }

        @Test
        @DisplayName("пустой батч — ноль без побочных эффектов")
        void emptyBatch() {
            when(repository.claimPurgeBatch(anyInt())).thenReturn(List.of());
            assertEquals(0, service.purgeExpiredBatch(100));
        }
    }
}
