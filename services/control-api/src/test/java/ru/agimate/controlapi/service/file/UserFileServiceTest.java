package ru.agimate.controlapi.service.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.manage.dto.files.FileListItemResponse;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.enums.FileStatus;
import ru.agimate.controlapi.database.repositories.StoredFileRepository;
import ru.agimate.controlapi.storage.FileIds;
import ru.agimate.controlapi.storage.FileStorageService;
import ru.agimate.controlapi.storage.SignedFileUrlService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
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
    @DisplayName("list")
    class ListFiles {

        @Test
        @DisplayName("каждая строка получает свежую подпись — хранить URL нельзя, он протухает")
        void signsEveryItem() {
            StoredFile stored = file("отчёт.csv", "text/csv", AGENT_ID);
            when(storedFileRepository.findVisible(eq(USER_ID), isNull(), isNull(),
                    any(LocalDateTime.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(stored)));
            when(signedFileUrlService.issue(anyString())).thenReturn("/files/x?exp=1&sig=s");

            Page<FileListItemResponse> page = service.list(USER_ID, null, null, 0, 20);

            FileListItemResponse item = page.getContent().getFirst();
            assertEquals(FileIds.external(stored.getId()), item.id());
            assertEquals("отчёт.csv", item.name());
            assertEquals("file", item.type());
            assertEquals(AGENT_ID, item.agentId());
            assertEquals("sheets:export", item.origin());
            assertEquals("/files/x?exp=1&sig=s", item.url());
            verify(signedFileUrlService).issue(FileIds.external(stored.getId()));
        }

        @Test
        @DisplayName("тип выводится из mime — фронт решает, рисовать ли картинку")
        void derivesTypeFromMime() {
            when(storedFileRepository.findVisible(any(), any(), any(),
                    any(LocalDateTime.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(file(null, "image/png", null))));

            FileListItemResponse item = service.list(USER_ID, null, null, 0, 20).getContent().getFirst();

            assertEquals("image", item.type());
            // У телеграм-фото и генерации имени нет — фронту нужен фолбэк.
            assertNull(item.name());
        }

        @Test
        @DisplayName("пустой фильтр по имени не сужает выборку")
        void blankNameIsNoFilter() {
            when(storedFileRepository.findVisible(eq(USER_ID), eq(AGENT_ID), isNull(),
                    any(LocalDateTime.class), any(Pageable.class)))
                    .thenReturn(Page.empty());

            service.list(USER_ID, AGENT_ID, "   ", 0, 20);

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(storedFileRepository).findVisible(eq(USER_ID), eq(AGENT_ID), isNull(),
                    any(LocalDateTime.class), pageable.capture());
            assertEquals(PageRequest.of(0, 20), pageable.getValue());
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
