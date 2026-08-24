package ru.agimate.controlapi.service.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.TooManyRequestsStatusException;
import ru.agimate.controlapi.config.FileStorageProperties;
import ru.agimate.controlapi.controller.manage.dto.files.FileListItemResponse;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.enums.FileStatus;
import ru.agimate.controlapi.database.repositories.StoredFileRepository;
import ru.agimate.controlapi.service.ratelimit.InboundRateLimiter;
import ru.agimate.controlapi.storage.FileIds;
import ru.agimate.controlapi.storage.FileLink;
import ru.agimate.controlapi.storage.FileStorageService;
import ru.agimate.controlapi.storage.NewFile;
import ru.agimate.controlapi.storage.SignedFileUrlService;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserFileService — файлы пользователя в manage")
class UserFileServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    @Mock
    private StoredFileRepository storedFileRepository;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private SignedFileUrlService signedFileUrlService;
    @Mock
    private InboundRateLimiter rateLimiter;
    @Spy
    private final FileStorageProperties fileStorageProperties = new FileStorageProperties();

    @InjectMocks
    private UserFileService service;

    private static StoredFile file(String name, String mime, UUID agentId) {
        return StoredFile.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .agentId(agentId)
                .status(FileStatus.READY)
                .mime(mime)
                .name(name)
                .sizeBytes(42L)
                .origin("sheets:export")
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
    }

    @Nested
    @DisplayName("upload")
    class Upload {

        private static MockMultipartFile multipart(String name, String contentType) {
            return new MockMultipartFile("file", name, contentType,
                    "payload".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("файл ложится под пользователя без агента — получателя выбирают позже")
        void storesUnderUserWithoutAgent() {
            StoredFile stored = file("отчёт.csv", "text/csv", null);
            when(rateLimiter.tryAcquire(InboundRateLimiter.Scope.FILE_UPLOAD, USER_ID)).thenReturn(true);
            when(fileStorageService.store(any(NewFile.class), any())).thenReturn(stored);
            when(signedFileUrlService.issue(any(FileLink.class))).thenReturn("/files/x?exp=1&sig=s");

            FileListItemResponse response = service.upload(USER_ID, multipart("отчёт.csv", "text/csv"), null);

            ArgumentCaptor<NewFile> spec = ArgumentCaptor.forClass(NewFile.class);
            verify(fileStorageService).store(spec.capture(), any());
            assertEquals(USER_ID, spec.getValue().userId());
            assertNull(spec.getValue().agentId());
            assertEquals("user", spec.getValue().origin());
            assertEquals("отчёт.csv", spec.getValue().name());
            // Свой файл живёт дольше коннекторного: недельное окно обессмыслило бы «что я присылал».
            assertEquals(Duration.ofDays(90), spec.getValue().ttl());
            // Загрузка — ещё не использование: контекст появится, когда файл приложат к сообщению.
            assertNull(spec.getValue().sessionId());
            // Ответ — то же представление, что и в листинге: у файла одна форма, а не две.
            assertEquals(FileIds.external(stored.getId()), response.id());
            assertEquals("/files/x?exp=1&sig=s", response.url());
        }

        @Test
        @DisplayName("без Content-Type — octet-stream, иначе строка mime уедет пустой")
        void defaultsMissingMime() {
            when(rateLimiter.tryAcquire(InboundRateLimiter.Scope.FILE_UPLOAD, USER_ID)).thenReturn(true);
            when(fileStorageService.store(any(NewFile.class), any()))
                    .thenReturn(file("blob", "application/octet-stream", null));

            assertDoesNotThrow(() -> service.upload(USER_ID, multipart("blob", null), null));

            ArgumentCaptor<NewFile> spec = ArgumentCaptor.forClass(NewFile.class);
            verify(fileStorageService).store(spec.capture(), any());
            assertEquals("application/octet-stream", spec.getValue().mime());
        }

        @Test
        @DisplayName("метка клиента живёт под префиксом — чужим провенансом не представиться")
        void namespacesClientOrigin() {
            when(rateLimiter.tryAcquire(InboundRateLimiter.Scope.FILE_UPLOAD, USER_ID)).thenReturn(true);
            when(fileStorageService.store(any(NewFile.class), any()))
                    .thenReturn(file("shot.png", "image/png", null));

            service.upload(USER_ID, multipart("shot.png", "image/png"), "board");

            ArgumentCaptor<NewFile> spec = ArgumentCaptor.forClass(NewFile.class);
            verify(fileStorageService).store(spec.capture(), any());
            assertEquals("user:board", spec.getValue().origin());
        }

        @Test
        @DisplayName("метка вне алфавита — 400 до хранилища, а не молчаливая правка")
        void rejectsForeignLookingOrigin() {
            when(rateLimiter.tryAcquire(InboundRateLimiter.Scope.FILE_UPLOAD, USER_ID)).thenReturn(true);

            assertThrows(BadRequestStatusException.class,
                    () -> service.upload(USER_ID, multipart("shot.png", "image/png"), "telegram:42"));
            verifyNoInteractions(fileStorageService);
        }

        @Test
        @DisplayName("лимит проверяется до хранилища — байты не должны доехать")
        void rateLimitStopsBeforeStorage() {
            when(rateLimiter.tryAcquire(InboundRateLimiter.Scope.FILE_UPLOAD, USER_ID)).thenReturn(false);

            assertThrows(TooManyRequestsStatusException.class,
                    () -> service.upload(USER_ID, multipart("shot.png", "image/png"), null));
            verifyNoInteractions(fileStorageService);
        }
    }

    @Nested
    @DisplayName("list")
    class ListFiles {

        @Test
        @DisplayName("каждая строка получает свежую подпись — хранить URL нельзя, он протухает")
        void signsEveryItem() {
            StoredFile stored = file("отчёт.csv", "text/csv", AGENT_ID);
            when(storedFileRepository.findVisible(eq(USER_ID), isNull(), isNull(), isNull(),
                    any(LocalDateTime.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(stored)));
            when(signedFileUrlService.issue(any(FileLink.class))).thenReturn("/files/x?exp=1&sig=s");

            Page<FileListItemResponse> page = service.list(USER_ID, null, null, null, 0, 20);

            FileListItemResponse item = page.getContent().getFirst();
            assertEquals(FileIds.external(stored.getId()), item.id());
            assertEquals("отчёт.csv", item.name());
            assertEquals("file", item.type());
            assertEquals(AGENT_ID, item.agentId());
            assertEquals("sheets:export", item.origin());
            assertEquals("/files/x?exp=1&sig=s", item.url());
            verify(signedFileUrlService).issue(FileLink.of(stored));
        }

        @Test
        @DisplayName("тип выводится из mime — фронт решает, рисовать ли картинку")
        void derivesTypeFromMime() {
            when(storedFileRepository.findVisible(any(), any(), any(), any(),
                    any(LocalDateTime.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(file(null, "image/png", null))));

            FileListItemResponse item = service.list(USER_ID, null, null, null, 0, 20)
                    .getContent().getFirst();

            assertEquals("image", item.type());
            // У телеграм-фото и генерации имени нет — фронту нужен фолбэк.
            assertNull(item.name());
        }

        @Test
        @DisplayName("пустой фильтр по имени не сужает выборку")
        void blankNameIsNoFilter() {
            when(storedFileRepository.findVisible(eq(USER_ID), eq(AGENT_ID), isNull(), isNull(),
                    any(LocalDateTime.class), any(Pageable.class)))
                    .thenReturn(Page.empty());

            service.list(USER_ID, AGENT_ID, null, "   ", 0, 20);

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(storedFileRepository).findVisible(eq(USER_ID), eq(AGENT_ID), isNull(), isNull(),
                    any(LocalDateTime.class), pageable.capture());
            assertEquals(PageRequest.of(0, 20), pageable.getValue());
        }

        @Test
        @DisplayName("сессия доезжает до выборки — фильтр разговора живёт в file_references")
        void passesSessionFilter() {
            UUID sessionId = UUID.randomUUID();
            when(storedFileRepository.findVisible(eq(USER_ID), isNull(), eq(sessionId), isNull(),
                    any(LocalDateTime.class), any(Pageable.class)))
                    .thenReturn(Page.empty());

            service.list(USER_ID, null, sessionId, null, 0, 20);

            verify(storedFileRepository).findVisible(eq(USER_ID), isNull(), eq(sessionId), isNull(),
                    any(LocalDateTime.class), any(Pageable.class));
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("файл помечается просроченным — блоб снимет общая чистка")
        void expiresInsteadOfDeleting() {
            StoredFile stored = file("shot.png", "image/png", null);
            String fileId = FileIds.external(stored.getId());
            when(fileStorageService.findReadable(USER_ID, fileId)).thenReturn(Optional.of(stored));

            service.delete(USER_ID, fileId);

            assertFalse(stored.getExpiresAt().isAfter(LocalDateTime.now()));
            verify(storedFileRepository).save(stored);
            // Своего пути удаления у сервиса нет: блоб трогает только purgeExpiredBatch.
            verify(storedFileRepository, never()).delete(any());
        }

        @Test
        @DisplayName("чужой или неизвестный id — 404, причины неразличимы")
        void foreignFileIsNotFound() {
            when(fileStorageService.findReadable(eq(USER_ID), anyString())).thenReturn(Optional.empty());

            assertThrows(NotFoundStatusException.class, () -> service.delete(USER_ID, "agf_" + UUID.randomUUID()));
            verify(storedFileRepository, never()).save(any());
        }
    }
}
