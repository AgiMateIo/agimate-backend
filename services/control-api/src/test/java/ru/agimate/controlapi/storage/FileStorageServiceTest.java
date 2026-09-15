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
import ru.agimate.controlapi.database.enums.FileReferenceKind;
import ru.agimate.controlapi.database.enums.FileStatus;
import ru.agimate.controlapi.database.entities.StoredFileVersion;
import ru.agimate.controlapi.database.repositories.StoredFileRepository;
import ru.agimate.controlapi.database.repositories.StoredFileVersionRepository;
import ru.agimate.controlapi.service.file.FileReferenceService;

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
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock
    private StoredFileVersionRepository versionRepository;
    @Mock
    private FileReferenceService fileReferenceService;

    /** In-memory BlobStore: put читает стрим (иначе не посчитается SHA-256), delete идемпотентен. */
    private static class InMemoryBlobStore implements BlobStore {
        final Map<String, byte[]> blobs = new HashMap<>();

        @Override
        public void put(String key, InputStream content, long contentLength, ResponseHeaders headers) {
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
        service = new FileStorageService(repository, versionRepository, blobStore, props, fileReferenceService);
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
            when(versionRepository.sumBytesSince(eq(USER_ID), any())).thenReturn(0L);

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
        @DisplayName("продюсер назвал сессию — файл получает ссылку на разговор здесь, а не у продюсера")
        void recordsToolReferenceWhenSessionIsKnown() {
            UUID sessionId = UUID.randomUUID();
            UUID agentId = UUID.randomUUID();
            when(versionRepository.sumBytesSince(eq(USER_ID), any())).thenReturn(0L);

            StoredFile file = service.store(NewFile.builder()
                    .userId(USER_ID).agentId(agentId).sessionId(sessionId)
                    .origin("media:gpt-image").mime("image/png").sizeBytes(5).build(), bytes("hello"));

            verify(fileReferenceService).record(file.getId(), sessionId, agentId, FileReferenceKind.TOOL);
        }

        @Test
        @DisplayName("сессии нет — ссылку поставят канальные воронки, когда файл дойдёт до разговора")
        void skipsReferenceWithoutSession() {
            when(versionRepository.sumBytesSince(eq(USER_ID), any())).thenReturn(0L);

            service.store(spec(USER_ID, "webchat", "text/plain", 5), bytes("hello"));

            verifyNoInteractions(fileReferenceService);
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
            when(versionRepository.sumBytesSince(eq(USER_ID), any())).thenReturn(98L);
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
            when(versionRepository.sumBytesSince(eq(USER_ID), any())).thenReturn(0L);
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
            when(versionRepository.sumBytesSince(eq(USER_ID), any())).thenReturn(0L);

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
            when(versionRepository.sumBytesSince(eq(owner), any())).thenReturn(0L);
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
            assertEquals(FileIds.external(file.getId()), content.link().fileId());
            assertEquals(1, content.link().version());
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
    @DisplayName("storeVersion")
    class StoreVersion {

        private StoredFile file;
        private String fileId;

        @BeforeEach
        void readyFile() {
            when(versionRepository.sumBytesSince(eq(USER_ID), any())).thenReturn(0L);
            file = service.store(NewFile.builder().userId(USER_ID).origin("files").name("a.md")
                    .mime("text/markdown").sizeBytes(5).build(), bytes("hello"));
            fileId = FileIds.external(file.getId());
            when(repository.findById(file.getId())).thenReturn(Optional.of(file));
        }

        private NewFile versionSpec(String name, String mime) {
            return NewFile.builder().userId(USER_ID).origin("files").name(name).mime(mime).sizeBytes(0).build();
        }

        private static byte[] text(String s) {
            return s.getBytes(StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("захват → заливка под ключ попытки → коммит")
        void claimsUploadsCommits() {
            when(repository.claimVersion(eq(file.getId()), eq(1), any(), any())).thenReturn(2);
            when(repository.commitVersion(eq(file.getId()), eq(2), eq("text/markdown"), eq(5L), any(),
                    any(), eq("files"), any(), any(), any())).thenReturn(1);

            service.storeVersion(fileId, 1, versionSpec(null, "text/markdown"), text("world"));

            assertArrayEquals(text("world"), blobStore.blobs.get(USER_ID + "/" + fileId + ".v2"));
            assertArrayEquals(text("hello"), blobStore.blobs.get(USER_ID + "/" + fileId),
                    "version 1 stays where it was");
        }

        @Test
        @DisplayName("захват отклонён — отказ без заливки")
        void refusedClaimUploadsNothing() {
            when(repository.claimVersion(eq(file.getId()), eq(1), any(), any())).thenReturn(null);

            assertThrows(FileVersionConflictException.class,
                    () -> service.storeVersion(fileId, 1, versionSpec(null, "text/markdown"), text("world")));

            assertEquals(1, blobStore.blobs.size());
        }

        @Test
        @DisplayName("коммит не прошёл (попытку перехватили) — удаляется только свой блоб")
        void lostCommitDeletesOwnBlob() {
            when(repository.claimVersion(eq(file.getId()), eq(1), any(), any())).thenReturn(2);
            when(repository.commitVersion(any(), eq(2), any(), eq(5L), any(), any(), any(), any(), any(), any()))
                    .thenReturn(0);

            assertThrows(FileVersionConflictException.class,
                    () -> service.storeVersion(fileId, 1, versionSpec(null, "text/markdown"), text("world")));

            assertNull(blobStore.blobs.get(USER_ID + "/" + fileId + ".v2"));
            assertNotNull(blobStore.blobs.get(USER_ID + "/" + fileId));
        }

        @Test
        @DisplayName("прочитанная версия устарела — отказ до захвата")
        void staleExpectedVersion() {
            file.setVersion(3);

            assertThrows(FileVersionConflictException.class,
                    () -> service.storeVersion(fileId, 2, versionSpec(null, "text/markdown"), text("world")));

            verify(repository, org.mockito.Mockito.never()).claimVersion(any(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("то же содержимое и mime — версии нет, имя и срок пишутся в файл")
        void identicalContentOnlyTouches() {
            when(repository.touch(eq(file.getId()), eq("b.md"), any(), any())).thenReturn(1);

            service.storeVersion(fileId, 1, versionSpec("b.md", "text/markdown"), text("hello"));

            verify(repository, org.mockito.Mockito.never()).claimVersion(any(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("то же содержимое, но файл удалён между чтением и записью — не найден")
        void identicalContentOnDeletedFile() {
            when(repository.touch(any(), any(), any(), any())).thenReturn(0);

            assertThrows(StoredFileNotFoundException.class,
                    () -> service.storeVersion(fileId, 1, versionSpec(null, "text/markdown"), text("hello")));
        }
    }

    @Nested
    @DisplayName("open с версией")
    class OpenVersion {

        @Test
        @DisplayName("закреплённая прежняя версия читается из журнала и своего ключа")
        void opensPinnedVersion() throws Exception {
            when(versionRepository.sumBytesSince(eq(USER_ID), any())).thenReturn(0L);
            StoredFile file = service.store(spec(USER_ID, "t", "text/plain", 5), bytes("hello"));
            String fileId = FileIds.external(file.getId());
            file.setVersion(3);
            file.setMime("text/html");
            when(repository.findById(file.getId())).thenReturn(Optional.of(file));
            when(versionRepository.findByFileIdAndVersion(file.getId(), 2)).thenReturn(Optional.of(
                    StoredFileVersion.builder().fileId(file.getId()).version(2).mime("text/markdown")
                            .sizeBytes(3L).build()));
            blobStore.blobs.put(USER_ID + "/" + fileId + ".v2", "old".getBytes(StandardCharsets.UTF_8));

            FileStorageService.FileContent content = service.open(USER_ID, fileId, 2);

            assertEquals("old", new String(content.content().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("text/markdown", content.mime());
            assertEquals(3L, content.size());
            assertEquals(2, content.link().version());
        }

        @Test
        @DisplayName("версии нет в журнале (сирота или выдумка) — файл недоступен")
        void rejectsUnknownVersion() {
            when(versionRepository.sumBytesSince(eq(USER_ID), any())).thenReturn(0L);
            StoredFile file = service.store(spec(USER_ID, "t", "text/plain", 5), bytes("hello"));
            file.setVersion(3);
            when(repository.findById(file.getId())).thenReturn(Optional.of(file));
            when(versionRepository.findByFileIdAndVersion(file.getId(), 2)).thenReturn(Optional.empty());

            assertThrows(StoredFileNotFoundException.class,
                    () -> service.open(USER_ID, FileIds.external(file.getId()), 2));
        }
    }

    @Nested
    @DisplayName("purgeExpiredBatch")
    class Purge {

        @Test
        @DisplayName("удаляет блоб и строку для каждого файла из батча")
        void purgesBlobAndRow() {
            when(versionRepository.sumBytesSince(eq(USER_ID), any())).thenReturn(0L);
            StoredFile file = service.store(spec(USER_ID, "t", "text/plain", 5), bytes("hello"));
            when(repository.claimPurgeBatch(anyInt())).thenReturn(List.of(file));

            int purged = service.purgeExpiredBatch(100);

            assertEquals(1, purged);
            assertTrue(blobStore.blobs.isEmpty());
            verify(repository).delete(file);
        }

        @Test
        @DisplayName("удаляет блоб каждой выданной попытки, включая незакоммиченную")
        void purgesEveryClaimedAttempt() {
            when(versionRepository.sumBytesSince(eq(USER_ID), any())).thenReturn(0L);
            StoredFile file = service.store(spec(USER_ID, "t", "text/plain", 5), bytes("hello"));
            String base = USER_ID + "/" + FileIds.external(file.getId());
            blobStore.blobs.put(base + ".v2", new byte[]{2});
            blobStore.blobs.put(base + ".v3", new byte[]{3});
            file.setVersion(2);
            file.setClaimedVersion(3);
            when(repository.claimPurgeBatch(anyInt())).thenReturn(List.of(file));

            service.purgeExpiredBatch(100);

            assertTrue(blobStore.blobs.isEmpty());
        }

        @Test
        @DisplayName("пустой батч — ноль без побочных эффектов")
        void emptyBatch() {
            when(repository.claimPurgeBatch(anyInt())).thenReturn(List.of());
            assertEquals(0, service.purgeExpiredBatch(100));
        }
    }
}
